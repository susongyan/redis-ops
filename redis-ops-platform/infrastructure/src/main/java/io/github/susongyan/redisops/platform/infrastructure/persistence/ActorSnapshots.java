package io.github.susongyan.redisops.platform.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves identity only on a new action; historical rows are never enriched on reads. */
@Component
public class ActorSnapshots {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ActorSnapshots(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }
    public String capture(String subject) {
        if (subject == null || !subject.matches("user:[1-9][0-9]*"))
            return null;
        long id = Long.parseLong(subject.substring(5));
        var rows = jdbc.query(
                "SELECT u.id,u.display_name,c.login_name FROM platform_user u LEFT JOIN platform_local_credential c ON c.user_id=u.id WHERE u.id=?",
                (rs, n) -> Map.of("userId", rs.getLong("id"), "login",
                        rs.getString("login_name") == null ? "" : rs.getString("login_name"), "displayName",
                        rs.getString("display_name")),
                id);
        if (rows.isEmpty())
            throw new IllegalStateException("ACTOR_NOT_FOUND");
        try {
            return json.writeValueAsString(rows.get(0));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("ACTOR_SNAPSHOT_FAILED");
        }
    }
}
