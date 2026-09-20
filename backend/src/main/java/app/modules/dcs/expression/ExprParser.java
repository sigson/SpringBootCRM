package app.modules.dcs.expression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <h2>Разбор выражений языка компоновки данных.</h2>
 *
 * <p>Лексер и рекурсивный спуск в одном классе — грамматика компактная, а держать её
 * целиком перед глазами полезнее, чем дробить по файлам.
 *
 * <p>Ключевые слова принимаются и по-русски, и по-английски ({@code ВЫБОР}/{@code CASE},
 * {@code И}/{@code AND}), регистр не важен — как в 1С. Имена функций не резервируются:
 * парсер видит любой {@code ИМЯ(…)} как {@link Expr.Call}, а какие из них поддержаны,
 * решает {@link ExprEvaluator}. Благодаря этому добавление функции не трогает разбор.
 *
 * <p>Приоритеты (от слабого к сильному): {@code ИЛИ} → {@code И} → {@code НЕ} →
 * сравнения (включая {@code В}, {@code МЕЖДУ}, {@code ЕСТЬ NULL}, {@code ПОДОБНО}) →
 * {@code + -} → {@code * /} → унарный минус → первичное выражение.
 */
public final class ExprParser {

    /** Ошибка разбора с позицией — её текст показывается прямо в редакторе выражений. */
    public static class ParseException extends RuntimeException {
        public final int position;
        public ParseException(String message, int position) {
            super(message + " (position " + position + ")");
            this.position = position;
        }
    }

    // ------------------------------------------------------------------ API

    /** Разбирает выражение целиком; хвост после последнего токена считается ошибкой. */
    public static Expr parse(String source) {
        ExprParser p = new ExprParser(source);
        Expr e = p.expression();
        p.expect(Tok.Kind.EOF, "end of expression");
        return e;
    }

    /** Разбор без выброса: {@code null}, если выражение пустое или некорректное. */
    public static Expr parseQuietly(String source) {
        if (source == null || source.isBlank()) return null;
        try { return parse(source); } catch (RuntimeException e) { return null; }
    }

    // -------------------------------------------------------------- лексика

    private record Tok(Kind kind, String text, Object value, int pos) {
        enum Kind { NUMBER, STRING, IDENT, PARAM, OP, LPAREN, RPAREN, COMMA, EOF }
    }

    /** Синонимы ключевых слов: русское написание → каноническое. */
    private static final Map<String, String> KEYWORDS = Map.ofEntries(
            Map.entry("ВЫБОР", "CASE"), Map.entry("CASE", "CASE"),
            Map.entry("КОГДА", "WHEN"), Map.entry("WHEN", "WHEN"),
            Map.entry("ТОГДА", "THEN"), Map.entry("THEN", "THEN"),
            Map.entry("ИНАЧЕ", "ELSE"), Map.entry("ELSE", "ELSE"),
            Map.entry("КОНЕЦ", "END"), Map.entry("END", "END"),
            Map.entry("И", "AND"), Map.entry("AND", "AND"),
            Map.entry("ИЛИ", "OR"), Map.entry("OR", "OR"),
            Map.entry("НЕ", "NOT"), Map.entry("NOT", "NOT"),
            Map.entry("ЕСТЬ", "IS"), Map.entry("IS", "IS"),
            Map.entry("NULL", "NULL"), Map.entry("НЕОПРЕДЕЛЕНО", "NULL"),
            Map.entry("ИСТИНА", "TRUE"), Map.entry("TRUE", "TRUE"),
            Map.entry("ЛОЖЬ", "FALSE"), Map.entry("FALSE", "FALSE"),
            Map.entry("ПОДОБНО", "LIKE"), Map.entry("LIKE", "LIKE"),
            Map.entry("В", "IN"), Map.entry("IN", "IN"),
            Map.entry("МЕЖДУ", "BETWEEN"), Map.entry("BETWEEN", "BETWEEN"));

    private static final Set<String> COMPARISONS = Set.of("=", "<>", "!=", ">", ">=", "<", "<=");

    private final String src;
    private final List<Tok> tokens = new ArrayList<>();
    private int idx;

    private ExprParser(String source) {
        this.src = source == null ? "" : source;
        tokenize();
    }

    private void tokenize() {
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }

            // Комментарий до конца строки.
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '(') { tokens.add(new Tok(Tok.Kind.LPAREN, "(", null, i)); i++; continue; }
            if (c == ')') { tokens.add(new Tok(Tok.Kind.RPAREN, ")", null, i)); i++; continue; }
            if (c == ',') { tokens.add(new Tok(Tok.Kind.COMMA, ",", null, i)); i++; continue; }

            // Строковый литерал: одинарные или двойные кавычки, удвоение экранирует.
            if (c == '\'' || c == '"') {
                char q = c;
                int start = i;
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n) {
                    char ch = src.charAt(i);
                    if (ch == q) {
                        if (i + 1 < n && src.charAt(i + 1) == q) { sb.append(q); i += 2; continue; }
                        i++;
                        break;
                    }
                    sb.append(ch);
                    i++;
                }
                tokens.add(new Tok(Tok.Kind.STRING, sb.toString(), sb.toString(), start));
                continue;
            }

            if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                String num = src.substring(start, i);
                tokens.add(new Tok(Tok.Kind.NUMBER, num, new BigDecimal(num), start));
                continue;
            }

            if (c == '&') {
                int start = i;
                i++;
                int s2 = i;
                while (i < n && isIdentChar(src.charAt(i))) i++;
                tokens.add(new Tok(Tok.Kind.PARAM, src.substring(s2, i), null, start));
                continue;
            }

            if (isIdentStart(c)) {
                int start = i;
                while (i < n && (isIdentChar(src.charAt(i)) || src.charAt(i) == '.')) i++;
                String word = src.substring(start, i);
                String kw = KEYWORDS.get(word.toUpperCase(Locale.ROOT));
                tokens.add(kw != null
                        ? new Tok(Tok.Kind.OP, kw, null, start)
                        : new Tok(Tok.Kind.IDENT, word, null, start));
                continue;
            }

            // Операторы: сначала двухсимвольные.
            if (i + 1 < n) {
                String two = src.substring(i, i + 2);
                if (COMPARISONS.contains(two)) {
                    tokens.add(new Tok(Tok.Kind.OP, two, null, i));
                    i += 2;
                    continue;
                }
            }
            String one = String.valueOf(c);
            if (COMPARISONS.contains(one) || "+-*/%".contains(one)) {
                tokens.add(new Tok(Tok.Kind.OP, one, null, i));
                i++;
                continue;
            }
            throw new ParseException("Unexpected character «" + c + "»", i);
        }
        tokens.add(new Tok(Tok.Kind.EOF, "", null, src.length()));
    }

    private static boolean isIdentStart(char c) { return Character.isLetter(c) || c == '_' || c == '[' || c == ']'; }
    private static boolean isIdentChar(char c)  { return Character.isLetterOrDigit(c) || c == '_' || c == '[' || c == ']'; }

    // ---------------------------------------------------------- рекурсивный спуск

    private Expr expression() { return or(); }

    private Expr or() {
        Expr left = and();
        while (isOp("OR")) { next(); left = new Expr.Binary("OR", left, and()); }
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (isOp("AND")) { next(); left = new Expr.Binary("AND", left, not()); }
        return left;
    }

    private Expr not() {
        if (isOp("NOT")) { next(); return new Expr.Unary("NOT", not()); }
        return comparison();
    }

    private Expr comparison() {
        Expr left = additive();
        while (true) {
            Tok t = peek();
            if (t.kind() != Tok.Kind.OP) return left;
            String op = t.text();

            if (COMPARISONS.contains(op)) {
                next();
                left = new Expr.Binary(normalizeCompare(op), left, additive());
            } else if ("LIKE".equals(op)) {
                next();
                left = new Expr.Binary("LIKE", left, additive());
            } else if ("IN".equals(op)) {
                next();
                left = new Expr.In(left, parenList(), false);
            } else if ("BETWEEN".equals(op)) {
                next();
                Expr low = additive();
                expectOp("AND", "«И» between the bounds of BETWEEN");
                left = new Expr.Between(left, low, additive());
            } else if ("IS".equals(op)) {
                next();
                boolean negated = false;
                if (isOp("NOT")) { next(); negated = true; }
                expectOp("NULL", "NULL after «ЕСТЬ»");
                left = new Expr.IsNull(left, negated);
            } else {
                return left;
            }
        }
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (isOp("+") || isOp("-")) {
            String op = next().text();
            left = new Expr.Binary(op, left, multiplicative());
        }
        return left;
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (isOp("*") || isOp("/") || isOp("%")) {
            String op = next().text();
            left = new Expr.Binary(op, left, unary());
        }
        return left;
    }

    private Expr unary() {
        if (isOp("-")) { next(); return new Expr.Unary("-", unary()); }
        if (isOp("+")) { next(); return unary(); }
        return primary();
    }

    private Expr primary() {
        Tok t = peek();
        switch (t.kind()) {
            case NUMBER, STRING -> { next(); return new Expr.Lit(t.value()); }
            case PARAM -> { next(); return new Expr.Param(t.text()); }
            case LPAREN -> {
                next();
                Expr inner = expression();
                expect(Tok.Kind.RPAREN, "»)«");
                return inner;
            }
            case IDENT -> {
                next();
                if (peek().kind() == Tok.Kind.LPAREN) {
                    return new Expr.Call(t.text(), parenList());
                }
                return new Expr.Field(t.text());
            }
            case OP -> {
                switch (t.text()) {
                    case "CASE" -> { return caseExpr(); }
                    case "NULL" -> { next(); return new Expr.Lit(null); }
                    case "TRUE" -> { next(); return new Expr.Lit(Boolean.TRUE); }
                    case "FALSE" -> { next(); return new Expr.Lit(Boolean.FALSE); }
                    default -> { }
                }
            }
            default -> { }
        }
        throw new ParseException("Unexpected token «" + t.text() + "»", t.pos());
    }

    private Expr caseExpr() {
        expectOp("CASE", "ВЫБОР");
        List<Expr.Branch> branches = new ArrayList<>();
        while (isOp("WHEN")) {
            next();
            Expr when = expression();
            expectOp("THEN", "ТОГДА");
            branches.add(new Expr.Branch(when, expression()));
        }
        if (branches.isEmpty()) {
            throw new ParseException("ВЫБОР requires at least one КОГДА", peek().pos());
        }
        Expr otherwise = null;
        if (isOp("ELSE")) { next(); otherwise = expression(); }
        expectOp("END", "КОНЕЦ");
        return branches.size() == 1 && otherwise == null
                ? new Expr.Case(branches, null)
                : new Expr.Case(branches, otherwise);
    }

    /** Список в скобках: аргументы функции или правая часть {@code В (…)}. */
    private List<Expr> parenList() {
        expect(Tok.Kind.LPAREN, "«(»");
        List<Expr> args = new ArrayList<>();
        if (peek().kind() != Tok.Kind.RPAREN) {
            args.add(expression());
            while (peek().kind() == Tok.Kind.COMMA) { next(); args.add(expression()); }
        }
        expect(Tok.Kind.RPAREN, "«)»");
        return args;
    }

    // ------------------------------------------------------------- служебное

    private static String normalizeCompare(String op) { return "!=".equals(op) ? "<>" : op; }

    private Tok peek() { return tokens.get(idx); }
    private Tok next() { return tokens.get(idx++); }

    private boolean isOp(String text) {
        Tok t = peek();
        return t.kind() == Tok.Kind.OP && t.text().equals(text);
    }

    private void expect(Tok.Kind kind, String what) {
        Tok t = peek();
        if (t.kind() != kind) {
            throw new ParseException("Expected " + what + ", got «" + t.text() + "»", t.pos());
        }
        next();
    }

    private void expectOp(String op, String what) {
        if (!isOp(op)) {
            Tok t = peek();
            throw new ParseException("Expected " + what + ", got «" + t.text() + "»", t.pos());
        }
        next();
    }
}
