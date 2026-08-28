package io.modernia.pixerion.server.auth;

import com.jayway.jsonpath.JsonPath;
import io.modernia.pixerion.server.auth.models.RoleRepository;
import io.modernia.pixerion.server.auth.models.User;
import io.modernia.pixerion.server.auth.models.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the authorization rules with <em>real</em> minted tokens rather than
 * {@code SecurityMockMvcRequestPostProcessors.jwt()}. That matters: the post-processor
 * injects authorities directly and would pass even if the {@code roles} claim and the
 * {@code JwtAuthenticationConverter} disagreed — which is precisely the failure this
 * suite needs to catch.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityRulesTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    RoleRepository roles;
    @Autowired
    PasswordEncoder passwordEncoder;

    /** A second account holding only ROLE_USER, to prove the ADMIN rule actually bites. */
    @BeforeEach
    void seedPlainUser() {
        if (users.existsByEmail("user@pixerion.local")) {
            return;
        }
        User user = new User();
        user.setEmail("user@pixerion.local");
        user.setPassword(passwordEncoder.encode("changeme"));
        user.addRole(roles.findByName("ROLE_USER"));
        users.save(user);
    }

    private String tokenFor(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"changeme\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    @Test
    void api_without_a_token_is_401() throws Exception {
        mvc.perform(get("/api/anything"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void api_with_a_token_gets_past_security() throws Exception {
        // 404, not 401: authentication succeeded and the request fell through to a path
        // that has no handler. That distinction is the whole point of this assertion.
        mvc.perform(get("/api/anything").header(AUTHORIZATION, "Bearer " + tokenFor("admin@pixerion.local")))
                .andExpect(status().isNotFound());
    }

    @Test
    void admin_token_reaches_an_admin_only_endpoint() throws Exception {
        // The full chain in one assertion: user_roles rows -> GrantedAuthority ->
        // `roles` claim -> JwtAuthenticationConverter -> hasRole("ADMIN").
        mvc.perform(get("/actuator/info").header(AUTHORIZATION, "Bearer " + tokenFor("admin@pixerion.local")))
                .andExpect(status().isOk());
    }

    @Test
    void user_token_is_forbidden_from_an_admin_only_endpoint() throws Exception {
        // 403, not 401 — authenticated, but lacking the authority.
        mvc.perform(get("/actuator/info").header(AUTHORIZATION, "Bearer " + tokenFor("user@pixerion.local")))
                .andExpect(status().isForbidden());
    }

    @Test
    void health_stays_open_for_the_container_probe() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void unclassified_paths_are_denied_not_exposed() throws Exception {
        mvc.perform(get("/nope"))
                .andExpect(status().isUnauthorized());
    }
}
