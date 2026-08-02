package com.modularbank.accounts.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    public UUID validateAndExtractUserId(String token) {
        DecodedJWT decodedJwt = JWT.require(Algorithm.HMAC256(secret))
            .build()
            .verify(token);

        return UUID.fromString(decodedJwt.getSubject());
    }
}
