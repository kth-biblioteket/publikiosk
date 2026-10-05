#!/bin/bash
# Kontrollerar config/catalog.json: giltig JSON i rätt format, och att varje nyckel läses av appen.
# Katalogen talar om för publicomtools vilka inställningar appen förstår, så en ny nyckel läggs
# till i samma commit som koden som läser den. Körs av CI och kan köras lokalt: tools/check-catalog.sh
cd "$(dirname "$0")/.." || exit 1
FAIL=0
if ! jq -e '.version == 1 and (.groups | length > 0) and (.keys | length > 0)' config/catalog.json > /dev/null; then
    echo "FEL: config/catalog.json är inte giltig eller har fel format"
    exit 1
fi
for key in $(jq -r '.keys[].key' config/catalog.json); do
    if ! grep -rqw "\"$key\"" app/src/main/java; then
        echo "FEL: $key finns i catalog.json men läses inte av appen"
        FAIL=1
    fi
done
dups=$(jq -r '.keys[].key' config/catalog.json | sort | uniq -d)
[ -n "$dups" ] && { echo "FEL: dubbletter i catalog.json: $dups"; FAIL=1; }
[ $FAIL -eq 0 ] && echo "ok: $(jq '.keys | length' config/catalog.json) nycklar, alla läses av appen"
exit $FAIL
