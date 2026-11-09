#!/usr/bin/env bash
# Generate artifacts for staging deploy (run locally; do not commit private keys or plaintext passwords).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND="$ROOT/backend"
PASSWORD="${1:-}"

echo "=== RSA signing key (PKCS#8 PEM) for OMS_STAGING_JWT_RSA_PRIVATE_KEY ==="
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | openssl pkcs8 -topk8 -nocrypt
echo
echo "Suggested kid for OMS_STAGING_JWT_KEY_ID: staging-oms-1"
echo

if [[ -z "$PASSWORD" ]]; then
  echo "Pass one password per demo user to print bcrypt hashes, e.g.:"
  echo "  $0 'alice-pass' 'bob-pass'"
  exit 0
fi

CP="$(
  cd "$BACKEND"
  ./mvnw -q dependency:build-classpath -Dmdep.includeScope=compile -Dmdep.outputFile=/tmp/staging-cp && cat /tmp/staging-cp
)"
cat > /tmp/BcryptStagingGen.java <<'JAVA'
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class BcryptStagingGen {
  public static void main(String[] args) {
    BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
    for (String arg : args) {
      System.out.println(enc.encode(arg));
    }
  }
}
JAVA
javac -cp "$CP" /tmp/BcryptStagingGen.java -d /tmp
echo "=== bcrypt password_hash values (paste into demo-accounts JSON / OMS_STAGING_DEMO_ACCOUNTS) ==="
java -cp "/tmp:$CP" BcryptStagingGen "$@"
