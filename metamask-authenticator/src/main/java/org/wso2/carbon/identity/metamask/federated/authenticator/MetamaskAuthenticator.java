/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.metamask.federated.authenticator;

import java.util.Arrays;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;
import java.io.*;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.oltu.oauth2.client.request.OAuthClientRequest;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;
import org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.FederatedApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.core.ServiceURLBuilder;
import org.wso2.carbon.identity.core.URLBuilderException;
import java.math.BigInteger;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class MetamaskAuthenticator extends AbstractApplicationAuthenticator
        implements FederatedApplicationAuthenticator {

    @Override
    public boolean canHandle(HttpServletRequest request) {

        return MetamaskAuthenticationConstants.LOGIN_TYPE.equals(getLoginType(request));
    }

    @Override
    public String getFriendlyName() {

        return MetamaskAuthenticationConstants.FRIENDLY_NAME;
    }

    @Override
    public String getName() {

        return MetamaskAuthenticationConstants.NAME;
    }

    @Override
    protected void initiateAuthenticationRequest(HttpServletRequest request, HttpServletResponse response,
            AuthenticationContext context) throws AuthenticationFailedException {

        // Get the session
        HttpSession session = request.getSession();
        // Create random message to get metamask signature
        String serverMessage = RandomStringUtils.randomAlphabetic(10);
        try {
            String authorizationEndPoint = "";
            authorizationEndPoint = ServiceURLBuilder.create()
                    .addPath(MetamaskAuthenticationConstants.LOGIN_PAGE_URL)
                    .build().getAbsolutePublicURL();
            String state = context.getContextIdentifier() + "," + MetamaskAuthenticationConstants.LOGIN_TYPE;
            OAuthClientRequest authRequest = OAuthClientRequest.authorizationLocation(authorizationEndPoint)
                    .setParameter(MetamaskAuthenticationConstants.SERVER_MESSAGE, serverMessage)
                    .setState(state).buildQueryMessage();
            // Set serverMessage to session
            session.setAttribute(MetamaskAuthenticationConstants.SERVER_MESSAGE, serverMessage);
            // Redirect user to metamask.jsp login page.
            String loginPage = authRequest.getLocationUri();
            response.sendRedirect(loginPage);
        } catch (OAuthSystemException | IOException | URLBuilderException e) {
            throw new AuthenticationFailedException(e.getMessage(), e);
        }
    }

    @Override
    protected void processAuthenticationResponse(HttpServletRequest request, HttpServletResponse response,
            AuthenticationContext context) throws AuthenticationFailedException {

        // Get the message sent to metamask for sign, in initiateAuthenticationRequest().
        HttpSession session = request.getSession(false);
        String serverMessage = (String) session.getAttribute(MetamaskAuthenticationConstants.SERVER_MESSAGE);
        String metamaskAddress = request.getParameter(MetamaskAuthenticationConstants.ADDRESS);
        String metamaskSignature = request.getParameter(MetamaskAuthenticationConstants.SIGNATURE);
        String addressRecovered = null;
        // Calculate the recovered address by passing serverMessage and metamaskSignature.
        addressRecovered = calculatePublicAddressFromMetamaskSignature(serverMessage, metamaskSignature);
        if (addressRecovered != null && addressRecovered.equals(metamaskAddress)) {
            AuthenticatedUser authenticatedUser = AuthenticatedUser
                    .createFederateAuthenticatedUserFromSubjectIdentifier(metamaskAddress);
            context.setSubject(authenticatedUser);
        } else {
            throw new AuthenticationFailedException(
                    MetamaskAuthenticationErrorConstants.ErrorMessages.INVALID_SIGNATURE.getCode(),
                    MetamaskAuthenticationErrorConstants.ErrorMessages.INVALID_SIGNATURE.getMessage());
        }
    }

    /**
     * Calculate public address from metamask signature and server generated message.
     *
     * @param serverMessage     String
     * @param metamaskSignature String
     * @return the recovered address from metamask signature using server generated message.
     */
    private static String calculatePublicAddressFromMetamaskSignature(String serverMessage,
            String metamaskSignature) {

        final String prefix = MetamaskAuthenticationConstants.PERSONAL_PREFIX + serverMessage.length();
        final byte[] msgHash = Hash.sha3((prefix + serverMessage).getBytes());
        final byte[] signatureBytes = Numeric.hexStringToByteArray(metamaskSignature);
        // Get the valid ECDSA curve point(v) from {r,s,v}
        byte validECPoint = signatureBytes[64];
        if (validECPoint < 27) {
            validECPoint += 27;
        }
        final Sign.SignatureData signatureData = new Sign.SignatureData(validECPoint,
                Arrays.copyOfRange(signatureBytes, 0, 32),
                Arrays.copyOfRange(signatureBytes, 32, 64));
        String addressRecovered = null;
        // Get the public key.
        final BigInteger publicKey = Sign.recoverFromSignature(validECPoint - 27, new ECDSASignature(
                new BigInteger(1, signatureData.getR()),
                new BigInteger(1, signatureData.getS())), msgHash);
        if (publicKey != null) {
            // Convert public key into public address
            addressRecovered = MetamaskAuthenticationConstants.METAMASK_ADDRESS_PREFIX + Keys.getAddress(publicKey);
        }
        return addressRecovered;

    }

    @Override
    public String getContextIdentifier(HttpServletRequest request) {

        String state = request.getParameter(MetamaskAuthenticationConstants.OAUTH2_PARAM_STATE);
        if (state != null) {
            return state.split(",")[0];
        } else {
            return null;
        }
    }

    private String getLoginType(HttpServletRequest request) {

        String state = request.getParameter(MetamaskAuthenticationConstants.OAUTH2_PARAM_STATE);
        if (state != null) {
            String[] stateElements = state.split(",");
            if (stateElements.length > 1) {
                return stateElements[1];
            }
        }
        return null;
    }

}
