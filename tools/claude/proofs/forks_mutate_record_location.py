import sys
p = sys.argv[1]; s = open(p).read()
body = ('  local fork="$1" key\n'
        '  key="${fork//%/%25}"\n'
        '  key="${key//\\//%2F}"\n'
        '  printf \'%s/shipped/%s.txt\\n\' "$STATE_DIR" "$key"\n')
assert body in s, "shipped_record_path body not found verbatim"
s = s.replace(body, '  local fork="$1"\n  printf \'%s/%s\\n\' "$fork" "$SHIPPED_MANIFEST_REL"\n')
open(p, 'w').write(s)
print("  mutation applied: the record is put back inside the worker's scratch")
