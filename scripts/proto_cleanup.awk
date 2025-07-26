#!/usr/bin/env awk -f

# This script removes buf specs (content within square brackets) from .proto files,
# including nested brackets. It also removes option declarations for buf.validate.

BEGIN {
    in_bracket = 0
    bracket_depth = 0
    in_option = 0
}

{
    line = $0
    output = ""
    for (i = 1; i <= length(line); i++) {
        char = substr(line, i, 1)
        if (char == "[") {
            bracket_depth++
            in_bracket = 1
        } else if (char == "]") {
            bracket_depth--
            if (bracket_depth == 0) {
                in_bracket = 0
            }
        } else if (!in_bracket) {
            if (match(substr(line, i), /^option *\(buf\.validate/)) {
                in_option = 1
                i += RLENGTH - 1
            } else if (in_option && char == ";") {
                in_option = 0
            } else if (!in_option) {
                output = output char
            }
        }
    }
    if (output != "" || (!in_bracket && !in_option)) {
        print output
    }
}

END {
    if (bracket_depth != 0) {
        print "Warning: Unmatched brackets in input file" > "/dev/stderr"
    }
    if (in_option) {
        print "Warning: Unclosed option declaration in input file" > "/dev/stderr"
    }
}
