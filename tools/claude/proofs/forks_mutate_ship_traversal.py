import sys
p = sys.argv[1]; s = open(p).read()
arm = ('  case "/$rel/" in\n'
       '    */../*)\n'
       "      fail \"cited source '$rel' has a '..' component and would be written outside $fork; nothing outside the fork was created\"\n"
       '      ;;\n'
       '  esac\n')
assert arm in s, "traversal arm not found verbatim"
open(p, 'w').write(s.replace(arm, ''))
print("  mutation applied: ONLY the '..' arm removed; the empty/absolute arm kept")
