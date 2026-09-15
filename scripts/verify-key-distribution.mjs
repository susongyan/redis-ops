// Disposable integration environment only. Never connects to configured business assets.
import {execFileSync, spawn} from 'node:child_process';
import {mkdtempSync, writeFileSync, readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {randomBytes} from 'node:crypto';
import {createServer} from 'node:net';

const dir = mkdtempSync(join(tmpdir(), 'redis-ops-distribution-'));
const password = randomBytes(24).toString('hex');
const containers = [];
let apiProcess;
const redisImage = process.env.DISTRIBUTION_TEST_REDIS_IMAGE || 'redis:7.4-alpine';
const run = (bin, args, options = {}) => {
  try { return execFileSync(bin, args, {encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe'], ...options}).trim(); }
  catch (e) { throw Error(`${bin} failed: ${String(e.stderr || e.stdout || '').replaceAll(password, '[REDACTED]').slice(-3000)}`); }
};
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => server.listen(0, '127.0.0.1', resolve).once('error', reject));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
function container(args) {
  const id = run('docker', ['run', '-d', '--name', `redis-ops-distribution-${randomBytes(5).toString('hex')}`, ...args]);
  containers.push(id); return id;
}
async function ready(fn) {
  for (let i = 0; i < 90; i++) { try { if (fn()) return; } catch {} await wait(1000); }
  throw Error('Isolated test service did not become ready');
}
try {
  const mysql = container(['-e', `MYSQL_ROOT_PASSWORD=${password}`, '-e', 'MYSQL_ROOT_HOST=%', '-p', '127.0.0.1::3306', 'mysql:8.4']);
  const sql = input => run('docker', ['exec', '-i', '-e', `MYSQL_PWD=${password}`, mysql, 'mysql', '-uroot', '--batch', '--skip-column-names'], {input});
  await ready(() => sql('SELECT 1') === '1');
  sql(readFileSync('redis-ops-platform/sql/latest/redis-governance-init.sql', 'utf8'));
  const mysqlPort = run('docker', ['port', mysql, '3306']).split(':').at(-1);
  const standalone = await freePort(), sentinel = await freePort();
  const redis = container(['-p', `127.0.0.1:${standalone}:${standalone}`, '-p', `127.0.0.1:${sentinel}:${sentinel}`,
    redisImage, 'redis-server', '--port', String(standalone), '--protected-mode', 'no', '--save', '', '--appendonly', 'no']);
  const sentinelFile = join(dir, 'sentinel.conf');
  writeFileSync(sentinelFile, `port ${sentinel}\nprotected-mode no\nsentinel monitor distribution-test 127.0.0.1 ${standalone} 1\n`);
  run('docker', ['cp', sentinelFile, `${redis}:/tmp/distribution-sentinel.conf`]);
  run('docker', ['exec', redis, 'redis-server', '/tmp/distribution-sentinel.conf', '--sentinel', '--daemonize', 'yes']);
  const ports = [];
  while (ports.length < 3) { const p = await freePort(); if (p < 55000 && !ports.includes(p)) ports.push(p); }
  const mapped = ports.flatMap(p => ['-p', `127.0.0.1:${p}:${p}`]);
  const cluster = container([...mapped, redisImage, 'redis-server', '--port', String(ports[0]), '--protected-mode', 'no',
    '--cluster-enabled', 'yes', '--cluster-config-file', `/tmp/nodes-${ports[0]}.conf`, '--save', '', '--appendonly', 'no']);
  for (const p of ports.slice(1)) run('docker', ['exec', cluster, 'redis-server', '--port', String(p), '--protected-mode', 'no',
    '--cluster-enabled', 'yes', '--cluster-config-file', `/tmp/nodes-${p}.conf`, '--daemonize', 'yes', '--save', '', '--appendonly', 'no']);
  run('docker', ['exec', cluster, 'redis-cli', '--cluster', 'create', ...ports.map(p => `127.0.0.1:${p}`), '--cluster-replicas', '0', '--cluster-yes']);
  await ready(() => run('docker', ['exec', cluster, 'redis-cli', '-p', String(ports[0]), 'CLUSTER', 'INFO']).includes('cluster_state:ok'));
  for (const [id, port] of [[redis, standalone], [cluster, ports[0]]]) {
    run('docker', ['exec', id, 'sh', '-c', `i=0; while [ "$i" -lt 120 ]; do redis-cli -c -p ${port} SET "business:$i" test >/dev/null || exit 1; i=$((i+1)); done`]);
  }
  run('unzip', ['-q', resolve('redis-ops-platform/bootstrap/target/redis-ops-platform-bootstrap-0.1.0-SNAPSHOT.jar'), 'BOOT-INF/lib/*', '-d', dir]);
  const cp = `${dir}:${join(dir, 'BOOT-INF/lib/*')}`;
  run('javac', ['-cp', cp, '-d', dir, resolve('scripts/fixtures/DistributionIntegration.java')]);
  console.log(run('java', ['-Xmx128m', '-cp', cp, 'DistributionIntegration', String(standalone), String(sentinel), String(ports[0])], {
    env: {...process.env, DISTRIBUTION_TEST_PASSWORD: password,
      DISTRIBUTION_TEST_JDBC: `jdbc:mysql://127.0.0.1:${mysqlPort}/redis_governance?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false`}
  }));
  run('javac', ['-cp', cp, '-d', dir, resolve('scripts/fixtures/DistributionMemoryProbe.java')]);
  console.log(run('java', ['-Xmx64m', '-cp', cp, 'DistributionMemoryProbe']));
  if (process.env.DISTRIBUTION_TEST_UI === '1') {
    if (!process.env.IDENTITY_BOOTSTRAP_PASSWORD) throw Error('UI QA requires IDENTITY_BOOTSTRAP_PASSWORD; first login must change it.');
    sql(`USE redis_governance; UPDATE redis_cluster SET endpoint='127.0.0.1:${standalone}' WHERE id=1;`);
    apiProcess = spawn('java', ['-jar', resolve('redis-ops-platform/bootstrap/target/redis-ops-platform-bootstrap-0.1.0-SNAPSHOT.jar'),
      '--server.address=127.0.0.1', '--server.port=8080', '--collector.enabled=false', '--platform.jobs.enabled=false', '--identity.cookie-secure=false'], {
      env: {...process.env, DB_URL: `jdbc:mysql://127.0.0.1:${mysqlPort}/redis_governance?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false`,
        DB_USERNAME: 'root', DB_PASSWORD: password, REDIS_OPS_CREDENTIAL_KEYS: `test:${randomBytes(32).toString('base64')}`},
      stdio: ['ignore', 'ignore', 'ignore']
    });
    console.log('Isolated API started on 127.0.0.1:8080 for UI QA (five-minute lifetime); only cluster 1 is wired for new scans.');
    await wait(300000);
  }
} finally {
  if (apiProcess) { apiProcess.kill('SIGTERM'); await wait(2000); }
  for (const id of containers.reverse()) run('docker', ['rm', '-f', '-v', id]);
  console.log('Removed only disposable distribution-test containers and their temporary volumes.');
}
