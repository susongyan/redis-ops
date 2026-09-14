package io.github.redisops.platform.infrastructure.identity;

import io.github.redisops.platform.domain.identity.*;
import io.github.redisops.platform.common.BusinessException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcUserRepository implements UserRepository {
    public io.github.redisops.platform.common.PageResult<Account> list(int page, int size) {
        var items = jdbc.query(
                "SELECT u.*,c.login_name FROM platform_user u LEFT JOIN platform_local_credential c ON c.user_id=u.id ORDER BY u.id LIMIT ? OFFSET ?",
                (r, n) -> new Account(map(r, n), r.getString("login_name"),
                        r.getString("login_name") == null ? "EXTERNAL" : "LOCAL"),
                size, (page - 1) * size);
        return new io.github.redisops.platform.common.PageResult<>(items,
                jdbc.queryForObject("SELECT COUNT(*) FROM platform_user", Long.class), page, size);
    }
    public void update(long id, String name, PlatformUser.Status status, PlatformUser.Role role, long version) {
        lockAdministration();
        var existing = get(id);
        boolean local = jdbc.queryForObject("SELECT COUNT(*) FROM platform_local_credential WHERE user_id=?",
                Integer.class, id) > 0;
        if (local && existing.status() == PlatformUser.Status.ACTIVE && existing.role() == PlatformUser.Role.ADMIN
                && (status != PlatformUser.Status.ACTIVE || role != PlatformUser.Role.ADMIN)
                && jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_user u JOIN platform_local_credential c ON c.user_id=u.id WHERE u.status='ACTIVE' AND u.role='ADMIN'",
                        Integer.class) <= 1)
            throw new BusinessException("LAST_LOCAL_ADMIN", "Cannot disable or demote the last local administrator");
        if (jdbc.update(
                "UPDATE platform_user SET display_name=?,status=?,role=?,version=version+1,authorization_version=authorization_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND version=?",
                name, status.name(), role.name(), id, version) != 1)
            throw new BusinessException("CONCURRENT_MODIFICATION", "User changed; reload and retry");
    }
    public void resetPassword(long id, String hash, long version) {
        lockAdministration();
        if (jdbc.update(
                "UPDATE platform_user SET password_change_required=TRUE,version=version+1,authorization_version=authorization_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND version=?",
                id, version) != 1)
            throw new BusinessException("CONCURRENT_MODIFICATION", "User changed; reload and retry");
        if (jdbc.update("UPDATE platform_local_credential SET password_hash=? WHERE user_id=?", hash, id) != 1)
            throw new BusinessException("LOCAL_USER_REQUIRED", "Cannot reset an external password");
    }
    private final JdbcTemplate jdbc;
    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    private static Instant instant(ResultSet row, String name) throws SQLException {
        var value = row.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    private static PlatformUser map(ResultSet r, int index) throws SQLException {
        String role = r.getString("role");
        return new PlatformUser(r.getLong("id"), r.getString("display_name"),
                PlatformUser.Status.valueOf(r.getString("status")),
                role == null ? null : PlatformUser.Role.valueOf(role), r.getBoolean("password_change_required"),
                r.getLong("version"),
                r.getLong("authorization_version"), instant(r, "created_at"), instant(r, "updated_at"),
                instant(r, "last_login_at"));
    }
    public PlatformUser get(long id) {
        return jdbc.query("SELECT * FROM platform_user WHERE id=?", JdbcUserRepository::map, id).stream().findFirst()
                .orElseThrow(() -> new BusinessException("RESOURCE_NOT_FOUND", "User not found"));
    }
    public Optional<LocalCredential> credential(String login) {
        return jdbc.query("SELECT user_id,password_hash FROM platform_local_credential WHERE login_name=?",
                (r, n) -> new LocalCredential(r.getLong(1), r.getString(2)), login).stream().findFirst();
    }
    public void lockAdministration() {
        jdbc.queryForObject("SELECT id FROM platform_auth_control WHERE id=1 FOR UPDATE", Integer.class);
    }
    public boolean initialized() {
        return Boolean.TRUE
                .equals(jdbc.queryForObject("SELECT initialized FROM platform_auth_control WHERE id=1", Boolean.class));
    }
    public void markInitialized() {
        jdbc.update("UPDATE platform_auth_control SET initialized=TRUE WHERE id=1");
    }
    public PlatformUser createLocal(String login, String name, String hash, PlatformUser.Role role) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO platform_user(display_name,status,role) VALUES (?,'ACTIVE',?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, role.name());
            return statement;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        jdbc.update(
                "INSERT INTO platform_identity(user_id,source,provider,subject,identity_digest) VALUES (?,'LOCAL','local',?,?)",
                id, login, digest("LOCAL\0local\0" + login));
        jdbc.update("INSERT INTO platform_local_credential(user_id,login_name,password_hash) VALUES (?,?,?)", id, login,
                hash);
        return get(id);
    }
    public void changePassword(long id, String hash, long version) {
        if (jdbc.update(
                "UPDATE platform_user SET password_change_required=FALSE,version=version+1,authorization_version=authorization_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND version=? AND status='ACTIVE'",
                id, version) != 1)
            throw new BusinessException("CONCURRENT_MODIFICATION", "User changed; reload and retry");
        if (jdbc.update("UPDATE platform_local_credential SET password_hash=? WHERE user_id=?", hash, id) != 1)
            throw new IllegalStateException("LOCAL_CREDENTIAL_MISSING");
    }
    public void recordLogin(long id) {
        jdbc.update("UPDATE platform_user SET last_login_at=CURRENT_TIMESTAMP(3) WHERE id=?", id);
    }
    public static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("DIGEST_UNAVAILABLE");
        }
    }
    @Transactional
    public boolean reserveAttempt(String key, int limit, int seconds) {
        jdbc.update(
                "INSERT IGNORE INTO platform_login_limit(limit_key,attempts,expires_at) VALUES (?,0,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)))",
                key, seconds);
        var rows = jdbc.queryForList(
                "SELECT attempts,expires_at<=CURRENT_TIMESTAMP(3) AS expired FROM platform_login_limit WHERE limit_key=? FOR UPDATE",
                key);
        var row = rows.get(0);
        boolean expired = ((Number) row.get("expired")).intValue() != 0;
        int attempts = expired ? 0 : ((Number) row.get("attempts")).intValue();
        if (attempts >= limit)
            return false;
        if (expired)
            jdbc.update(
                    "UPDATE platform_login_limit SET attempts=1,expires_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)) WHERE limit_key=?",
                    seconds, key);
        else
            jdbc.update("UPDATE platform_login_limit SET attempts=attempts+1 WHERE limit_key=?", key);
        return true;
    }
    public void clearAttempts(String key) {
        jdbc.update("DELETE FROM platform_login_limit WHERE limit_key=?", key);
    }
    public void cleanupAttempts() {
        jdbc.update("DELETE FROM platform_login_limit WHERE expires_at<CURRENT_TIMESTAMP(3) LIMIT 1000");
    }
}
