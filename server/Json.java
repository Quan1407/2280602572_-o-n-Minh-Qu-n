import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON parser/encoder (no external deps) for this project.
 * Supports objects, arrays, strings, numbers, booleans, null.
 */
public final class Json {
    private Json() {}

    public static Object parse(String json) {
        if (json == null) throw new IllegalArgumentException("json is null");
        Parser p = new Parser(json);
        Object v = p.readValue();
        p.skipWs();
        if (!p.eof()) throw new IllegalArgumentException("Trailing data at pos " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object v) {
        return (List<Object>) v;
    }

    public static String asString(Object v) {
        return (v == null) ? null : String.valueOf(v);
    }

    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Number n) {
            sb.append(n.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape((String) e.getKey())).append('"').append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> a) {
            sb.append('[');
            boolean first = true;
            for (Object x : a) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, x);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(v))).append('"');
        }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) { this.s = s; }

        boolean eof() { return i >= s.length(); }

        void skipWs() {
            while (!eof()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        char peek() {
            if (eof()) return '\0';
            return s.charAt(i);
        }

        char next() {
            if (eof()) throw new IllegalArgumentException("Unexpected end at pos " + i);
            return s.charAt(i++);
        }

        void expect(char c) {
            char got = next();
            if (got != c) throw new IllegalArgumentException("Expected '" + c + "' but got '" + got + "' at pos " + (i - 1));
        }

        Object readValue() {
            skipWs();
            char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> { readLiteral("true"); yield Boolean.TRUE; }
                case 'f' -> { readLiteral("false"); yield Boolean.FALSE; }
                case 'n' -> { readLiteral("null"); yield null; }
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield readNumber();
                    throw new IllegalArgumentException("Unexpected char '" + c + "' at pos " + i);
                }
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            skipWs();
            Map<String, Object> m = new LinkedHashMap<>();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object val = readValue();
                m.put(key, val);
                skipWs();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' at pos " + (i - 1));
            }
            return m;
        }

        List<Object> readArray() {
            expect('[');
            skipWs();
            List<Object> a = new ArrayList<>();
            if (peek() == ']') { i++; return a; }
            while (true) {
                Object v = readValue();
                a.add(v);
                skipWs();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or ']' at pos " + (i - 1));
            }
            return a;
        }

        String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (eof()) throw new IllegalArgumentException("Unterminated string");
                char c = next();
                if (c == '"') break;
                if (c == '\\') {
                    if (eof()) throw new IllegalArgumentException("Bad escape");
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw new IllegalArgumentException("Bad \\u escape");
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            out.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("Bad escape \\" + e);
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        Number readNumber() {
            int start = i;
            if (peek() == '-') i++;
            while (!eof() && Character.isDigit(peek())) i++;
            boolean isFloat = false;
            if (!eof() && peek() == '.') {
                isFloat = true;
                i++;
                while (!eof() && Character.isDigit(peek())) i++;
            }
            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                isFloat = true;
                i++;
                if (!eof() && (peek() == '+' || peek() == '-')) i++;
                while (!eof() && Character.isDigit(peek())) i++;
            }
            String num = s.substring(start, i);
            try {
                if (isFloat) return Double.parseDouble(num);
                long v = Long.parseLong(num);
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) return (int) v;
                return v;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad number '" + num + "'");
            }
        }

        void readLiteral(String lit) {
            for (int k = 0; k < lit.length(); k++) {
                char c = next();
                if (c != lit.charAt(k)) throw new IllegalArgumentException("Expected '" + lit + "' at pos " + (i - 1));
            }
        }
    }
}


