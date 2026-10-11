package com.modocument.api;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * QA, optimization round, test plan S2/S3/S4 (contracts §8, ws28):
 * <ul>
 *   <li>S2/S3: the removed AI proxy ({@code /api/aifiles/send}) and the
 *       removed {@code /api/transform/transform} answer 404 for every bank
 *       role and every method — not 401/403/405, which would reveal that
 *       something still lives there;</li>
 *   <li>S4: EVERY route in the deprecation table of
 *       {@code docs/api/versioning-policy.md} §7 answers with
 *       {@code Deprecation}, {@code Sunset} and {@code Link}
 *       (parameterised over the table, whatever the status the handler
 *       answers with), and a sample of current routes carries none of
 *       the three.</li>
 * </ul>
 * {@code DeprecatedEndpointsTest} pins one route and the OpenAPI parity;
 * this class widens it to the whole list.
 */
class ApiHygieneQaTest extends DetectionGateTestSupport {

    private static final Path POLICY = Path.of("docs", "api", "versioning-policy.md");
    private static final Pattern TABLE_ROUTE =
            Pattern.compile("^\\| `(GET|POST|PUT|DELETE|PATCH) (/[^`]+)` \\| 1\\.2\\.0 \\|");
    private static final String SUNSET = "Thu, 01 Apr 2027 00:00:00 GMT";
    private static final String LINK = "</docs/api/versioning-policy.md>; rel=\"deprecation\"";

    static Stream<Arguments> deprecatedRoutes() throws IOException {
        List<Arguments> routes = new ArrayList<>();
        for (String line : Files.readAllLines(POLICY)) {
            Matcher route = TABLE_ROUTE.matcher(line);
            if (route.find()) {
                routes.add(Arguments.of(route.group(1), route.group(2)));
            }
        }
        assertThat(routes).as("deprecation table rows").hasSize(20);
        return routes.stream();
    }

    private MockHttpServletRequestBuilder call(String method, String template, AppUser caller) {
        // Path variables -> an id nobody has: the headers must not depend on the outcome.
        String path = template.replaceAll("\\{[^}]+}", "999999");
        MockHttpServletRequestBuilder builder;
        if (path.equals("/api/fileupload/upload")) {
            builder = multipart(path).file(new MockMultipartFile("file", "qa-deprecated.csv",
                    "text/csv", "Name\nAda\n".getBytes()));
        } else {
            builder = request(HttpMethod.valueOf(method), path);
            if (!method.equals("GET") && !method.equals("DELETE")) {
                builder.contentType(MediaType.APPLICATION_JSON).content("{}");
            }
        }
        return builder.header("Authorization", bearer(caller));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("deprecatedRoutes")
    void everyDeprecatedRouteCarriesTheThreeHeaders(String method, String template) throws Exception {
        Bank bank = newBank("DPQ");
        AppUser admin = newUser(bank, "dpq-admin", Role.ADMIN, null);

        MockHttpServletResponse response = mvc.perform(call(method, template, admin))
                .andReturn().getResponse();

        // A handler's own 404 for the unknown id is fine; the headers prove the
        // deprecated handler was matched (they are set in preHandle).
        assertThat(response.getStatus()).as("%s %s answers", method, template)
                .isNotIn(405, 415)
                .isLessThan(500);
        assertThat(response.getHeader("Deprecation")).as("Deprecation on %s %s", method, template)
                .isEqualTo("true");
        assertThat(response.getHeader("Sunset")).isEqualTo(SUNSET);
        assertThat(response.getHeader("Link")).isEqualTo(LINK);
    }

    @Test
    void currentRoutesCarryNoDeprecationHeaders() throws Exception {
        Bank bank = newBank("DPQ");
        AppUser admin = newUser(bank, "dpq-admin", Role.ADMIN, null);
        for (String path : List.of("/api/auth/me", "/api/ingestion/jobs?view=summary&page=0&size=5",
                "/api/dashboard/summary", "/Templates/GetAll", "/TemplateDataTypes/GetAll",
                "/ImportHistory/GetAll", "/api/admin/users?page=0&size=5", "/api/admin/audit")) {
            MockHttpServletResponse response = mvc.perform(call("GET", path, admin))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).as(path).isEqualTo(200);
            assertThat(response.getHeader("Deprecation")).as("Deprecation on %s", path).isNull();
            assertThat(response.getHeader("Sunset")).as("Sunset on %s", path).isNull();
            assertThat(response.getHeader("Link")).as("Link on %s", path).isNull();
        }
    }

    @Test
    void theRemovedRoutesAre404ForEveryRoleAndMethod() throws Exception {
        Bank bank = newBank("RMV");
        List<String> answers = new ArrayList<>();
        for (Role role : List.of(Role.READ_ONLY, Role.READ_WRITE, Role.APPROVER, Role.ADMIN)) {
            AppUser caller = newUser(bank, "rmv-" + role.name().toLowerCase(), role, null);
            for (String path : List.of("/api/aifiles/send", "/api/transform/transform")) {
                for (String method : List.of("GET", "POST", "PUT")) {
                    int status = mvc.perform(call(method, path, caller)).andReturn().getResponse()
                            .getStatus();
                    answers.add(role + " " + method + " " + path + " -> " + status);
                    assertThat(status).as("%s %s %s", role, method, path).isEqualTo(404);
                }
            }
        }
        assertThat(answers).hasSize(24);
    }
}
