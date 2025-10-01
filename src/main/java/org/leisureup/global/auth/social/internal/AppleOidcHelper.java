package org.leisureup.global.auth.social.internal;

import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.*;
import java.io.*;
import java.math.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.Base64.*;
import lombok.*;
import lombok.extern.slf4j.*;
import org.leisureup.global.auth.social.*;
import org.leisureup.global.exception.*;
import org.springframework.stereotype.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppleOidcHelper {

    private static final String KID = "kid";
    private static final String SUB = "sub";
    private static final String APPLE_ISS = "https://appleid.apple.com";
    private static final Decoder base64Decoder = Base64.getUrlDecoder();
    private final ObjectMapper objMapper;

    private JsonNode getHeaderNodeFrom(String idToken) {
        String[] split = idToken.split("\\.");

        if (split.length != 3) {
            throw new InvalidIdentityTokenException(401, "Invalid id token encountered.");
        }

        String header = split[0];
        JsonNode headerNode;

        try {
            headerNode = objMapper.readTree(base64Decoder.decode(header));
        } catch (IOException e) {
            log.warn("Unexpected error occurred while reading header from token.", e);
            throw new RuntimeException(e);
        }

        return headerNode;
    }

    private static PublicKey getRSAPublicKey(String modulus, String exponent)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        KeyFactory rsaKeyFactory = KeyFactory.getInstance("RSA");

        byte[] decodeN = Base64.getUrlDecoder().decode(modulus);
        byte[] decodeE = Base64.getUrlDecoder().decode(exponent);

        BigInteger n = new BigInteger(1, decodeN);
        BigInteger e = new BigInteger(1, decodeE);

        return rsaKeyFactory.generatePublic(
                new RSAPublicKeySpec(n, e)
        );
    }

    /**
     * ID token (jwt) header 에서 kid 값을 가져온다.
     *
     * <li>참고로 JJWT 옛 버전에선 라이브러리에서 가능했지만 프로젝트에 적용된 버전은 불가능하도록 바뀌었다. 그래서 걍 직접 Base64 디코딩해서
     * 가져온다.</li>
     */
    public String getKidClaimsFrom(String idToken) {

        // jwt header 내용을 가져온다.
        JsonNode headerNode = getHeaderNodeFrom(idToken);
        JsonNode kidValNode = headerNode.get(KID);

        if (kidValNode == null) {
            throw new InvalidIdentityTokenException(401, "Cannot find kid attribute from token.");
        }

        String kid = kidValNode.asText();
        if (kid.isEmpty()) {
            throw new InvalidIdentityTokenException(401, "Empty kid value found from token.");
        }

        return kid;
    }

    /**
     * 주어진 정보로 ID token 을 검증한다.
     * <p>
     * Apple 문서에 따르면 다음 내용을 확인하라 칸다.
     *
     * <li>JWS 시그니처가 Apple OIDC public key 와 맞는지</li>
     * <li>{@code iss} 가 Apple 이 맞는지</li>
     * <li>{@code aud} 가 우리의 {@code client_id} 와 동일한지</li>
     * <li>토큰 발금 때 사용한 {@code nonce} 가 동일한지</li>
     * <li>토큰이 만료 되었는지</li>
     * <p>
     * 시간 없어서 {@code aud}, {@code nonce} 검증은 스킵...
     */
    public OAuthResponse getVerifiedInfoFrom(
            String idToken, String modulus, String exponent
    ) {
        Claims jwsClaims = getOidcJwsClaimsWith(idToken, modulus, exponent)
                .getPayload();

        String sub;
        try {
            sub = jwsClaims.get(SUB, String.class);
        } catch (Exception e) {
            log.error("Unexpected error occurred", e);
            throw new RuntimeException(e);
        }

        if (sub == null || sub.isEmpty()) {
            throw new InvalidIdentityTokenException(401,
                    "Cannot find sub id attribute from token.");
        }

        return OAuthResponse.of(sub, "User", "");
    }

    private Jws<Claims> getOidcJwsClaimsWith(
            String idToken, String modulus, String exponent
    ) {
        try {
            return Jwts.parser()
                    .verifyWith(getRSAPublicKey(modulus, exponent))
                    .requireIssuer(APPLE_ISS)
                    .build()
                    .parseSignedClaims(idToken);
        } catch (ExpiredJwtException e) {
            throw new InvalidIdentityTokenException(401, "Token has been expired.");
        } catch (IncorrectClaimException | MissingClaimException e) {
            throw new InvalidIdentityTokenException(401, "Invalid token issuer detected.");
        } catch (Exception e) {
            log.error("Unexpected exception occurred", e);
            throw new RuntimeException(e);
        }
    }
}
