import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const gradleVersion = '8.14.6';
const kotlinVersion = '2.4.20';
const r8Version = '9.1.56';
// Trusted values: https://gradle.org/release-checksums/ (8.14.6).
const distributionHash = '7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24';
const wrapperHash = 'ad16bca46cb71bb5887c546860d93992aeee35c1fdaf4a242b0a612375d937e0';
const actionCommits = new Map([
  ['actions/checkout', 'fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09'],
  ['actions/setup-java', 'b6effb05e454b25005698d916606bdc6ffcbf961'],
  ['actions/setup-node', 'a0853c24544627f65ddf259abe73b1d18a591444'],
  ['actions/upload-artifact', '330a01c490aca151604b8cf639adc76d48f6c5d4'],
  ['reactivecircus/android-emulator-runner', 'a421e43855164a8197daf9d8d40fe71c6996bb0d'],
]);

function check(configuration) {
  const errors = [];
  const properties = new Map(configuration.wrapperProperties.split(/\r?\n/)
    .filter(line => line && !line.startsWith('#'))
    .map(line => {
      const separator = line.indexOf('=');
      return [line.slice(0, separator), line.slice(separator + 1).replace(/\\:/g, ':')];
    }));
  if (properties.get('distributionUrl') !== `https://services.gradle.org/distributions/gradle-${gradleVersion}-bin.zip`) {
    errors.push(`Gradle must use the reviewed ${gradleVersion} distribution`);
  }
  if (properties.get('distributionSha256Sum') !== distributionHash) errors.push('Gradle distribution checksum is missing or incorrect');
  if (configuration.wrapperJarHash !== wrapperHash) errors.push('Wrapper JAR does not match the official checksum');
  const plugins = [...configuration.rootBuild.matchAll(/id\("(org\.jetbrains\.kotlin\.[^"]+)"\) version "([^"]+)"/g)];
  if (plugins.length !== 5 || plugins.some(([, , version]) => version !== kotlinVersion)) {
    errors.push(`All five Kotlin plugins must use the reviewed ${kotlinVersion}`);
  }
  if (!configuration.settingsBuild.includes(`classpath("com.android.tools:r8:${r8Version}")`)) {
    errors.push(`Kotlin 2.4 requires the reviewed R8 ${r8Version} override`);
  }
  for (const [name, build] of [['Android', configuration.androidBuild], ['Shared', configuration.sharedBuild]]) {
    if (!build.includes('languageVersion.set(KotlinVersion.KOTLIN_2_2)')
      || !build.includes('apiVersion.set(KotlinVersion.KOTLIN_2_2)')) {
      errors.push(`${name} must retain the reviewed Kotlin 2.2 language/metadata contract`);
    }
    if (!build.includes('implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")')
      || !build.includes('coreLibrariesVersion = "2.2.10"')) {
      errors.push(`${name} must retain the reviewed Kotlin stdlib 2.2.10 runtime`);
    }
  }
  if (!/^kapt\.incremental\.apt=false\s*$/m.test(configuration.gradleProperties)) {
    errors.push('KAPT incremental cache defense must remain enabled');
  }
  for (const coordinate of ['net.zetetic:sqlcipher-android:4.19.1', 'org.bouncycastle:bcprov-jdk18on:1.86']) {
    if (!configuration.androidBuild.includes(`implementation("${coordinate}")`)) {
      errors.push(`Android runtime must retain reviewed ${coordinate}`);
    }
  }
  const uses = [...configuration.workflow.matchAll(/^[ \t]*(?:-[ \t]+)?uses:[ \t]+([^\s#]+)[ \t]*(?:#.*)?$/gm)];
  if (!uses.length) errors.push('No CI actions found');
  for (const [, action] of uses) {
    const separator = action.lastIndexOf('@');
    const name = action.slice(0, separator);
    const sha = action.slice(separator + 1);
    if (actionCommits.get(name) !== sha) errors.push(`CI action must use its reviewed full commit: ${action}`);
  }
  if (!/^permissions:\s*\r?\n[ \t]+contents: read\s*$/m.test(configuration.workflow)
    || /\b[a-z-]+:\s*write\b/.test(configuration.workflow)
    || /\bpermissions:[ \t]*write-all\b/.test(configuration.workflow)
    || /\b(?:pull_request_target|workflow_run):/.test(configuration.workflow)) {
    errors.push('CI permissions or triggers exceed the reviewed read-only build policy');
  }
  return errors;
}

function selfTest() {
  const valid = {
    wrapperProperties: `distributionUrl=https\\://services.gradle.org/distributions/gradle-${gradleVersion}-bin.zip\ndistributionSha256Sum=${distributionHash}\n`,
    wrapperJarHash: wrapperHash,
    rootBuild: ['android', 'multiplatform', 'plugin.serialization', 'kapt', 'plugin.compose']
      .map(name => `id("org.jetbrains.kotlin.${name}") version "${kotlinVersion}" apply false`).join('\n'),
    settingsBuild: `dependencies { classpath("com.android.tools:r8:${r8Version}") }`,
    androidBuild: 'coreLibrariesVersion = "2.2.10"\nlanguageVersion.set(KotlinVersion.KOTLIN_2_2)\napiVersion.set(KotlinVersion.KOTLIN_2_2)\nimplementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")\nimplementation("net.zetetic:sqlcipher-android:4.19.1")\nimplementation("org.bouncycastle:bcprov-jdk18on:1.86")',
    sharedBuild: 'coreLibrariesVersion = "2.2.10"\nlanguageVersion.set(KotlinVersion.KOTLIN_2_2)\napiVersion.set(KotlinVersion.KOTLIN_2_2)\nimplementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")',
    gradleProperties: 'kapt.incremental.apt=false\n',
    workflow: `permissions:\n  contents: read\njobs:\n  check:\n    steps:\n${[...actionCommits].map(([name, sha]) => `      - uses: ${name}@${sha} # reviewed`).join('\n')}\n`,
  };
  assert.deepEqual(check(valid), []);
  const invalid = [
    { ...valid, wrapperProperties: valid.wrapperProperties.replace(gradleVersion, '8.13') },
    { ...valid, wrapperProperties: valid.wrapperProperties.replace(distributionHash, '') },
    { ...valid, wrapperJarHash: '0'.repeat(64) },
    { ...valid, rootBuild: valid.rootBuild.replace(kotlinVersion, '2.2.0') },
    { ...valid, settingsBuild: valid.settingsBuild.replace(r8Version, '8.11.18') },
    { ...valid, androidBuild: valid.androidBuild.replace('languageVersion.set(KotlinVersion.KOTLIN_2_2)', '') },
    { ...valid, androidBuild: valid.androidBuild.replace('apiVersion.set(KotlinVersion.KOTLIN_2_2)', '') },
    { ...valid, sharedBuild: valid.sharedBuild.replace('languageVersion.set(KotlinVersion.KOTLIN_2_2)', '') },
    { ...valid, sharedBuild: valid.sharedBuild.replace('apiVersion.set(KotlinVersion.KOTLIN_2_2)', '') },
    { ...valid, androidBuild: valid.androidBuild.replace('kotlin-stdlib:2.2.10', 'kotlin-stdlib:2.4.20') },
    { ...valid, sharedBuild: valid.sharedBuild.replace('kotlin-stdlib:2.2.10', 'kotlin-stdlib:2.4.20') },
    { ...valid, androidBuild: valid.androidBuild.replace('coreLibrariesVersion = "2.2.10"', '') },
    { ...valid, sharedBuild: valid.sharedBuild.replace('coreLibrariesVersion = "2.2.10"', '') },
    { ...valid, gradleProperties: 'kapt.incremental.apt=true\n' },
    { ...valid, androidBuild: valid.androidBuild.replace('sqlcipher-android:4.19.1', 'sqlcipher-android:4.18.0') },
    { ...valid, androidBuild: valid.androidBuild.replace('bcprov-jdk18on:1.86', 'bcprov-jdk18on:1.83') },
    { ...valid, workflow: valid.workflow.replace(actionCommits.get('actions/checkout'), 'v5') },
    { ...valid, workflow: `${valid.workflow}      - name: Upload report\n        uses: actions/upload-artifact@v5\n` },
    { ...valid, workflow: valid.workflow.replace('contents: read', 'contents: write') },
    { ...valid, workflow: `${valid.workflow}\n  other:\n    permissions: write-all\n` },
    { ...valid, workflow: `${valid.workflow}\n  other:\n    permissions:\n      security-events: write\n` },
    { ...valid, workflow: `on:\n  pull_request_target:\n${valid.workflow}` },
  ];
  for (const fixture of invalid) assert.ok(check(fixture).length > 0, 'Unsafe fixture was accepted');
  console.log(`Build-security self-tests: ${invalid.length + 1} passed`);
}

if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  const errors = check({
    wrapperProperties: readFileSync(resolve(root, 'gradle/wrapper/gradle-wrapper.properties'), 'utf8'),
    wrapperJarHash: createHash('sha256').update(readFileSync(resolve(root, 'gradle/wrapper/gradle-wrapper.jar'))).digest('hex'),
    rootBuild: readFileSync(resolve(root, 'build.gradle.kts'), 'utf8'),
    settingsBuild: readFileSync(resolve(root, 'settings.gradle.kts'), 'utf8'),
    androidBuild: readFileSync(resolve(root, 'apps/android/app/build.gradle.kts'), 'utf8'),
    sharedBuild: readFileSync(resolve(root, 'shared/core/build.gradle.kts'), 'utf8'),
    gradleProperties: readFileSync(resolve(root, 'gradle.properties'), 'utf8'),
    workflow: readFileSync(resolve(root, '.github/workflows/ci.yml'), 'utf8'),
  });
  if (errors.length) {
    console.error(errors.map(error => `ERROR: ${error}`).join('\n'));
    process.exitCode = 1;
  } else {
    console.log(`Build-security checks passed: Gradle ${gradleVersion}, Kotlin ${kotlinVersion}, verified Wrapper and pinned CI actions`);
  }
}
