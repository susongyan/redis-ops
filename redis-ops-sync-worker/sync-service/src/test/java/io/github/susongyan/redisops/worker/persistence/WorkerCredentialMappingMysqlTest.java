package io.github.susongyan.redisops.worker.persistence;

import io.github.susongyan.redisops.worker.config.WorkerCredentialDecryptor;
import java.sql.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in disposable local MySQL. Guards the credential row mapping against MyBatis silently
 * dropping columns whose field type has no TypeHandler (java.util.UUID has none).
 */
class WorkerCredentialMappingMysqlTest {
    private SqlSession session;
    private String database;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("SYNC_POLICY_TEST_MYSQL");
        Assumptions.assumeTrue(url != null && url.startsWith("jdbc:mysql://127.0.0.1:"));
        var configuration = new Configuration(new Environment("fixture", new JdbcTransactionFactory(),
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, "root", "")));
        configuration.addMapper(WorkerAssetMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        database = "sync_credential_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE DATABASE " + database);
        execute("USE " + database);
        execute("""
                CREATE TABLE redis_cluster_secret (cluster_id BIGINT PRIMARY KEY,secret_uuid CHAR(36) NOT NULL,
                encrypted_secret BLOB NOT NULL,key_id VARCHAR(64) NOT NULL,username VARCHAR(128),
                secret_status VARCHAR(32) NOT NULL DEFAULT 'ENCRYPTED',version BIGINT NOT NULL DEFAULT 0)
                """);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (session != null) {
            try {
                if (database != null && database.matches("sync_credential_test_[0-9a-f]{32}"))
                    execute("DROP DATABASE " + database);
            } finally {
                session.close();
            }
        }
    }

    @Test
    void mapsSecretUuidAndDecryptsEndToEnd() throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        UUID secretUuid = UUID.randomUUID();
        byte[] ciphertext = encrypt(secretUuid, key, "cluster4-password");
        try (var insert = session.getConnection().prepareStatement(
                "INSERT INTO redis_cluster_secret(cluster_id,secret_uuid,encrypted_secret,key_id,username)"
                        + " VALUES (4,?,?,?,?)")) {
            insert.setString(1, secretUuid.toString());
            insert.setBytes(2, ciphertext);
            insert.setString(3, "k1");
            insert.setString(4, "default");
            insert.executeUpdate();
        }
        WorkerAssetMapper.WorkerCredentialRow row = session.getMapper(WorkerAssetMapper.class).findCredential(4);
        assertNotNull(row);
        assertEquals(secretUuid.toString(), row.secretUuid, "secret_uuid must map; a UUID-typed field stays null");
        assertEquals("k1", row.keyId);
        assertEquals("ENCRYPTED", row.secretStatus);
        assertArrayEquals(ciphertext, row.encryptedSecret);
        char[] plain = new WorkerCredentialDecryptor("k1:" + Base64.getEncoder().encodeToString(key))
                .decrypt(UUID.fromString(row.secretUuid), row.encryptedSecret, row.keyId);
        assertArrayEquals("cluster4-password".toCharArray(), plain);
    }

    private static byte[] encrypt(UUID secretUuid, byte[] key, String value) throws Exception {
        byte[] iv = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("redis-credential:" + secretUuid).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.nio.ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute(sql);
        }
    }
}
