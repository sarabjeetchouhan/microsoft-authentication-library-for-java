// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.aad.msal4j;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.oauth2.sdk.ErrorObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.SerializeException;
import com.nimbusds.oauth2.sdk.TokenErrorResponse;
import com.nimbusds.oauth2.sdk.http.CommonContentTypes;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.util.URLUtils;
import com.nimbusds.openid.connect.sdk.token.OIDCTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.AccessLevel;
import lombok.Getter;

@Getter(AccessLevel.PACKAGE)
class TokenRequest {
    Logger log = LoggerFactory.getLogger(TokenRequest.class);

    final Authority requestAuthority;
    private final MsalRequest msalRequest;
    private final ServiceBundle serviceBundle;

    TokenRequest(Authority requestAuthority, MsalRequest msalRequest, ServiceBundle serviceBundle) {
        this.requestAuthority = requestAuthority;
        this.serviceBundle = serviceBundle;
        this.msalRequest = msalRequest;
    }

    /**
     *
     * @return
     * @throws ParseException
     * @throws AuthenticationException
     * @throws SerializeException
     * @throws IOException
     */
    AuthenticationResult executeOauthRequestAndProcessResponse()
            throws ParseException, AuthenticationException, SerializeException,
            IOException {

        HttpEvent httpEvent = createHttpEvent();

        try(TelemetryHelper telemetryHelper = serviceBundle.getTelemetryManager().createTelemetryHelper(
                msalRequest.requestContext().getTelemetryRequestId(),
                msalRequest.application().clientId(),
                httpEvent,
                false)) {

            AuthenticationResult result;
            HTTPResponse httpResponse;

            httpResponse = toOauthHttpRequest().send();

            addResponseHeadersToHttpEvent(httpEvent, httpResponse);

            if (httpResponse.getStatusCode() == HTTPResponse.SC_OK) {
                final TokenResponse response = TokenResponse.parseHttpResponse(httpResponse);

                OIDCTokens tokens = response.getOIDCTokens();
                String refreshToken = null;
                if (tokens.getRefreshToken() != null) {
                    refreshToken = tokens.getRefreshToken().getValue();
                }

                AccountCacheEntity accountCacheEntity = null;

                if (tokens.getIDToken() != null) {
                    String idTokenJson = tokens.getIDToken().getParsedParts()[1].decodeToString();
                    IdToken idToken = JsonHelper.convertJsonToObject(idTokenJson, IdToken.class);

                    if (!StringHelper.isBlank(response.getClientInfo())) {

                        AuthorityType type = msalRequest.application().authenticationAuthority.authorityType;
                        if(type == AuthorityType.B2C){

                            B2CAuthority authority = (B2CAuthority) msalRequest.application().authenticationAuthority;

                            accountCacheEntity = AccountCacheEntity.create(
                                    response.getClientInfo(),
                                    requestAuthority.host(),
                                    idToken,
                                    authority.policy);
                        } else {
                            accountCacheEntity = AccountCacheEntity.create(
                                    response.getClientInfo(),
                                    requestAuthority.host(),
                                    idToken);
                        }
                    }
                }
                long currTimestampSec = new Date().getTime() / 1000;

                result = AuthenticationResult.builder().
                        accessToken(tokens.getAccessToken().getValue()).
                        refreshToken(refreshToken).
                        familyId(response.getFoci()).
                        idToken(tokens.getIDTokenString()).
                        environment(requestAuthority.host()).
                        expiresOn(currTimestampSec + response.getExpiresIn()).
                        extExpiresOn(response.getExtExpiresIn() > 0 ? currTimestampSec + response.getExtExpiresIn() : 0).
                        accountCacheEntity(accountCacheEntity).
                        scopes(response.getScope()).
                        build();

            } else {
                final TokenErrorResponse errorResponse = TokenErrorResponse.parse(httpResponse);
                ErrorObject errorObject = errorResponse.getErrorObject();

                if (AuthenticationErrorCode.AUTHORIZATION_PENDING.toString()
                        .equals(errorObject.getCode())) {

                    httpEvent.setOauthErrorCode(AuthenticationErrorCode.AUTHORIZATION_PENDING.toString());

                    throw new AuthenticationException(AuthenticationErrorCode.AUTHORIZATION_PENDING,
                            errorObject.getDescription());
                }

                if (HTTPResponse.SC_BAD_REQUEST == errorObject.getHTTPStatusCode() &&
                        AuthenticationErrorCode.INTERACTION_REQUIRED.toString().equals(errorObject.getCode())) {

                    httpEvent.setOauthErrorCode(AuthenticationErrorCode.INTERACTION_REQUIRED.toString());

                    throw new ClaimsChallengeException(
                            errorResponse.toJSONObject().toJSONString(),
                            getClaims(httpResponse.getContent()));
                } else {
                    String telemetryErrorCode = StringHelper.isBlank(errorObject.getCode()) ?
                            AuthenticationErrorCode.UNKNOWN.toString() :
                            errorObject.getCode();

                    httpEvent.setOauthErrorCode(telemetryErrorCode);

                    throw new AuthenticationException(errorResponse.toJSONObject().toJSONString());
                }
            }
            return result;
        }
    }

    private void addResponseHeadersToHttpEvent(HttpEvent httpEvent, HTTPResponse httpResponse) {
        httpEvent.setHttpResponseStatus(httpResponse.getStatusCode());

        if (!StringHelper.isBlank(httpResponse.getHeaderValue("User-Agent"))) {
            httpEvent.setUserAgent(httpResponse.getHeaderValue("User-Agent"));
        }

        if (!StringHelper.isBlank(httpResponse.getHeaderValue("x-ms-request-id"))) {
            httpEvent.setRequestIdHeader(httpResponse.getHeaderValue("x-ms-request-id"));
        }

        if (!StringHelper.isBlank(httpResponse.getHeaderValue("x-ms-clitelem"))) {
            XmsClientTelemetryInfo xmsClientTelemetryInfo =
                    XmsClientTelemetryInfo.parseXmsTelemetryInfo(
                            httpResponse.getHeaderValue("x-ms-clitelem"));
            if (xmsClientTelemetryInfo != null) {
                httpEvent.setXmsClientTelemetryInfo(xmsClientTelemetryInfo);
            }
        }
    }

    private HttpEvent createHttpEvent() throws MalformedURLException {
        HttpEvent httpEvent = new HttpEvent();
        httpEvent.setHttpMethod("POST");
        try {
            httpEvent.setHttpPath(requestAuthority.tokenEndpointUrl().toURI());
            if(!StringHelper.isBlank(requestAuthority.tokenEndpointUrl().getQuery()))
                httpEvent.setQueryParameters(requestAuthority.tokenEndpointUrl().getQuery());
        } catch(URISyntaxException ex){
            log.warn(LogHelper.createMessage("Setting URL telemetry fields failed: " +
                            LogHelper.getPiiScrubbedDetails(ex),
                    msalRequest.headers().getHeaderCorrelationIdValue()));
        }
        return httpEvent;
    }

    private String getClaims(String httpResponseContentStr) {
        JsonElement root = new JsonParser().parse(httpResponseContentStr);

        JsonElement claims = root.getAsJsonObject().get("claims");

        return claims != null ? claims.getAsString() : null;
    }

    /**
     *
     * @return
     * @throws SerializeException
     */
    OAuthHttpRequest toOauthHttpRequest() throws SerializeException, MalformedURLException {

        if (requestAuthority.tokenEndpointUrl() == null) {
            throw new SerializeException("The endpoint URI is not specified");
        }

        final OAuthHttpRequest oauthHttpRequest = new OAuthHttpRequest(
                HTTPRequest.Method.POST,
                requestAuthority.tokenEndpointUrl(),
                msalRequest.headers().getReadonlyHeaderMap(),
                this.serviceBundle);
        oauthHttpRequest.setContentType(CommonContentTypes.APPLICATION_URLENCODED);

        final Map<String, List<String>> params = msalRequest.msalAuthorizationGrant().toParameters();

        oauthHttpRequest.setQuery(URLUtils.serializeParameters(params));

        if (msalRequest.application().clientAuthentication != null) {
            msalRequest.application().clientAuthentication.applyTo(oauthHttpRequest);
        }

        return oauthHttpRequest;
    }
}