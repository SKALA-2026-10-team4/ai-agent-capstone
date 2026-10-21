package com.capstone4.skillops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

@WebMvcTest(AuthController.class)
@Import({ SecurityConfig.class, TokenService.class })
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtDecoder decoder;

    private String login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void login_issues_signed_token_with_roles() throws Exception {
        String body = login("operator", "operator1234");

        assertThat(JsonPath.<String>read(body, "$.token_type")).isEqualTo("Bearer");
        assertThat(JsonPath.<Integer>read(body, "$.expires_in")).isEqualTo(7200);
        assertThat(JsonPath.<String>read(body, "$.user.display_name")).isEqualTo("운영자");
        Jwt jwt = decoder.decode(JsonPath.read(body, "$.access_token"));
        assertThat(jwt.getSubject()).isEqualTo("operator");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("OPERATOR");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void wrong_password_and_unknown_user_get_the_same_401() throws Exception {
        for (String[] attempt : new String[][] { { "operator", "nope" }, { "ghost", "operator1234" } }) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + attempt[0] + "\",\"password\":\"" + attempt[1] + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    @Test
    void blank_fields_are_rejected() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void me_returns_token_user_and_requires_login() throws Exception {
        String token = JsonPath.read(login("owner", "owner1234"), "$.access_token");

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("owner"))
                .andExpect(jsonPath("$.roles[0]").value("OWNER"));
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
