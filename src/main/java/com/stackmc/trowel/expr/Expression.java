package com.stackmc.trowel.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A compiled math expression, in WorldEdit syntax and beyond.
 *
 * <p>{@code x*x + y*y < 0.5}, {@code r=sqrt(x^2+z^2); r < 1 + 0.2*sin(y*6)},
 * {@code if (y > 0) { 1 } else { perlin(0, x, y, z, 2, 3, 0.5) }}. True means strictly
 * positive, as in WorldEdit. Input variables have a fixed slot; any other assigned variable
 * becomes a local variable, reset to zero on every evaluation.</p>
 *
 * <ul>
 *   <li>operators: {@code + - * / % ^ **}, {@code == != ~= < <= > >=}, {@code && || !},
 *       {@code << >>}, {@code ? :}, assignments {@code = += -= *= /= %= ^=}, {@code ++ --};</li>
 *   <li>statements: {@code ;}, blocks {@code { }}, {@code if/else}, {@code while}, {@code do/while},
 *       {@code for (i = a, b)}, {@code for (init; cond; step)}, {@code break}, {@code continue},
 *       {@code return};</li>
 *   <li>functions: see {@link Functions}.</li>
 * </ul>
 *
 * <p>Compiled once, the expression is a tree of nodes that evaluates without allocating: it can
 * run millions of times in a computation. An evaluation shares nothing between threads.</p>
 */
public final class Expression {

    /** Safeguard: beyond this, a loop is considered infinite. */
    static final int MAX_ITERATIONS = 100_000;

    private final String source;
    private final Node root;
    private final int slots;
    private final Map<String, Integer> names;
    private final ThreadLocal<Frame> frames;

    private Expression(String source, Node root, int slots, Map<String, Integer> names) {
        this.source = source;
        this.root = root;
        this.slots = slots;
        this.names = names;
        this.frames = ThreadLocal.withInitial(this::frame);
    }

    /**
     * Compiles an expression.
     *
     * @param inputs the variables supplied on each evaluation, in order
     * @throws IllegalArgumentException if the expression is malformed
     */
    public static Expression compile(String source, String... inputs) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Empty expression.");
        }
        Parser parser = new Parser(source, inputs);
        Node root = parser.program();
        return new Expression(source, root, parser.slots.size(), Map.copyOf(parser.slots));
    }

    public String source() {
        return source;
    }

    /** The slot of a variable, or -1 if the expression does not use it. */
    public int slot(String name) {
        Integer slot = names.get(name.toLowerCase(Locale.ROOT));
        return slot == null ? -1 : slot;
    }

    /** A fresh evaluation space, to keep for chaining evaluations on the same thread. */
    public Frame frame() {
        return new Frame(slots);
    }

    /** Evaluates with these inputs, in the order of {@link #compile}. */
    public double evaluate(double... inputs) {
        Frame frame = frames.get();
        frame.reset(inputs);
        return run(frame);
    }

    /** Evaluates in an already filled space (inputs set by {@link Frame#set}). */
    public double run(Frame frame) {
        frame.signal = Frame.NONE;
        frame.iterations = 0;
        double value = root.eval(frame);
        return frame.signal == Frame.RETURN ? frame.returned : value;
    }

    /** The evaluation space: the variables, and a way to query the world. */
    public static final class Frame {
        static final int NONE = 0;
        static final int BREAK = 1;
        static final int CONTINUE = 2;
        static final int RETURN = 3;

        final double[] vars;
        int signal;
        double returned;
        int iterations;
        /** The evaluated block, for queries relative to the world. */
        public int blockX;
        public int blockY;
        public int blockZ;
        /** What query functions read, or {@code null}. */
        public WorldQuery world;

        Frame(int slots) {
            vars = new double[Math.max(1, slots)];
        }

        void reset(double[] inputs) {
            java.util.Arrays.fill(vars, 0);
            System.arraycopy(inputs, 0, vars, 0, Math.min(inputs.length, vars.length));
        }

        /** Resets local variables to zero and sets the inputs. */
        public Frame inputs(double... inputs) {
            reset(inputs);
            return this;
        }

        public void set(int slot, double value) {
            if (slot >= 0) {
                vars[slot] = value;
            }
        }

        public double get(int slot) {
            return slot < 0 ? 0 : vars[slot];
        }
    }

    /** What an expression can ask of the world around the evaluated block. */
    public interface WorldQuery {
        boolean solid(int x, int y, int z);

        boolean air(int x, int y, int z);
    }

    // ------------------------------------------------------------------- nodes

    @FunctionalInterface
    interface Node {
        double eval(Frame f);
    }

    static boolean truth(double v) {
        return v > 0;
    }

    static double bool(boolean b) {
        return b ? 1 : 0;
    }

    // ----------------------------------------------------------------- parsing

    private enum Kind { NUMBER, NAME, OP, END }

    private record Token(Kind kind, String text, double number, int at) {
    }

    private static final class Parser {

        private static final String[] OPERATORS = {"**=", "^=", "+=", "-=", "*=", "/=", "%=", "==", "!=", "~=", "<=",
                ">=", "&&", "||", "<<", ">>", "++", "--", "**", "+", "-", "*", "/", "%", "^", "<", ">", "=", "!", "~",
                "?", ":", "(", ")", "{", "}", ",", ";"};

        private final String source;
        private final List<Token> tokens = new ArrayList<>();
        private final Map<String, Integer> slots = new LinkedHashMap<>();
        private int pos;

        Parser(String source, String[] inputs) {
            this.source = source;
            for (String input : inputs) {
                slots.putIfAbsent(input.toLowerCase(Locale.ROOT), slots.size());
            }
            lex();
        }

        private void lex() {
            int i = 0;
            int n = source.length();
            outer:
            while (i < n) {
                char c = source.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(source.charAt(i + 1)))) {
                    int start = i;
                    while (i < n && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) {
                        i++;
                    }
                    if (i < n && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
                        int save = i;
                        i++;
                        if (i < n && (source.charAt(i) == '+' || source.charAt(i) == '-')) {
                            i++;
                        }
                        if (i < n && Character.isDigit(source.charAt(i))) {
                            while (i < n && Character.isDigit(source.charAt(i))) {
                                i++;
                            }
                        } else {
                            i = save;
                        }
                    }
                    String text = source.substring(start, i);
                    try {
                        tokens.add(new Token(Kind.NUMBER, text, Double.parseDouble(text), start));
                    } catch (NumberFormatException e) {
                        throw error("unreadable number: " + text, start);
                    }
                    continue;
                }
                if (Character.isLetter(c) || c == '_' || c == '$') {
                    int start = i;
                    while (i < n && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_'
                            || source.charAt(i) == '$')) {
                        i++;
                    }
                    tokens.add(new Token(Kind.NAME, source.substring(start, i).toLowerCase(Locale.ROOT), 0, start));
                    continue;
                }
                for (String op : OPERATORS) {
                    if (source.startsWith(op, i)) {
                        tokens.add(new Token(Kind.OP, op, 0, i));
                        i += op.length();
                        continue outer;
                    }
                }
                throw error("unexpected character '" + c + "'", i);
            }
            tokens.add(new Token(Kind.END, "", 0, n));
        }

        private IllegalArgumentException error(String message, int at) {
            String around = source.length() > 60 ? source.substring(Math.max(0, at - 20),
                    Math.min(source.length(), at + 20)) : source;
            return new IllegalArgumentException("Expression: " + message + " (position " + (at + 1) + ", in '"
                    + around + "').");
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private boolean at(String op) {
            Token t = peek();
            return (t.kind == Kind.OP || t.kind == Kind.NAME) && t.text.equals(op);
        }

        private boolean accept(String op) {
            if (at(op)) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(String op) {
            if (!accept(op)) {
                Token t = peek();
                throw error("'" + op + "' expected" + (t.kind == Kind.END ? " before the end" : ", found '" + t.text + "'"),
                        t.at);
            }
        }

        private int slot(String name) {
            return slots.computeIfAbsent(name, key -> slots.size());
        }

        // ---------------------------------------------------- statements

        Node program() {
            Node body = statements(false);
            if (peek().kind != Kind.END) {
                throw error("unexpected '" + peek().text + "'", peek().at);
            }
            return body;
        }

        private Node statements(boolean inBlock) {
            List<Node> list = new ArrayList<>();
            while (true) {
                while (accept(";")) {
                    // Empty statements.
                }
                if (peek().kind == Kind.END || (inBlock && at("}"))) {
                    break;
                }
                list.add(statement());
                if (!accept(";") && !(inBlock && at("}")) && peek().kind != Kind.END && !endsWithBlock(list)) {
                    throw error("';' expected between two statements", peek().at);
                }
            }
            if (list.isEmpty()) {
                return f -> 0;
            }
            if (list.size() == 1) {
                return list.get(0);
            }
            Node[] all = list.toArray(new Node[0]);
            return f -> {
                double value = 0;
                for (Node node : all) {
                    value = node.eval(f);
                    if (f.signal != Frame.NONE) {
                        return value;
                    }
                }
                return value;
            };
        }

        private boolean lastWasBlock;

        private boolean endsWithBlock(List<Node> list) {
            boolean was = lastWasBlock;
            lastWasBlock = false;
            return was && !list.isEmpty();
        }

        private Node block() {
            if (accept("{")) {
                Node body = statements(true);
                expect("}");
                lastWasBlock = true;
                return body;
            }
            Node single = statement();
            return single;
        }

        private Node statement() {
            lastWasBlock = false;
            if (accept("if")) {
                expect("(");
                Node condition = expression();
                expect(")");
                Node then = block();
                Node otherwise = null;
                int save = pos;
                while (accept(";")) {
                    // A ';' may separate the block from the else.
                }
                if (accept("else")) {
                    otherwise = block();
                } else {
                    pos = save;
                }
                Node other = otherwise;
                lastWasBlock = true;
                return other == null
                        ? f -> truth(condition.eval(f)) ? then.eval(f) : 0
                        : f -> truth(condition.eval(f)) ? then.eval(f) : other.eval(f);
            }
            if (accept("while")) {
                expect("(");
                Node condition = expression();
                expect(")");
                Node body = block();
                lastWasBlock = true;
                return f -> {
                    double value = 0;
                    while (truth(condition.eval(f))) {
                        count(f);
                        value = body.eval(f);
                        if (loopEnd(f)) {
                            break;
                        }
                    }
                    return value;
                };
            }
            if (accept("do")) {
                Node body = block();
                expect("while");
                expect("(");
                Node condition = expression();
                expect(")");
                return f -> {
                    double value;
                    do {
                        count(f);
                        value = body.eval(f);
                        if (loopEnd(f)) {
                            break;
                        }
                    } while (truth(condition.eval(f)));
                    return value;
                };
            }
            if (accept("for")) {
                return forLoop();
            }
            if (accept("break")) {
                return f -> {
                    f.signal = Frame.BREAK;
                    return 0;
                };
            }
            if (accept("continue")) {
                return f -> {
                    f.signal = Frame.CONTINUE;
                    return 0;
                };
            }
            if (accept("return")) {
                Node value = at(";") || at("}") || peek().kind == Kind.END ? f -> 0 : expression();
                return f -> {
                    double v = value.eval(f);
                    f.returned = v;
                    f.signal = Frame.RETURN;
                    return v;
                };
            }
            if (at("{")) {
                return block();
            }
            return expression();
        }

        /** Returns true if the loop must stop; consumes break and continue. */
        private static boolean loopEnd(Frame f) {
            if (f.signal == Frame.BREAK) {
                f.signal = Frame.NONE;
                return true;
            }
            if (f.signal == Frame.CONTINUE) {
                f.signal = Frame.NONE;
                return false;
            }
            return f.signal == Frame.RETURN;
        }

        private static void count(Frame f) {
            if (++f.iterations > MAX_ITERATIONS) {
                throw new IllegalArgumentException("Expression: loop too long (more than " + MAX_ITERATIONS
                        + " iterations).");
            }
        }

        private Node forLoop() {
            expect("(");
            int save = pos;
            if (peek().kind == Kind.NAME && tokens.get(pos + 1).text.equals("=")) {
                String name = next().text;
                next();
                Node from = expression();
                if (accept(",")) {
                    Node to = expression();
                    expect(")");
                    Node body = block();
                    int slot = slot(name);
                    lastWasBlock = true;
                    return f -> {
                        double value = 0;
                        double end = to.eval(f);
                        for (double i = from.eval(f); i <= end; i++) {
                            count(f);
                            f.vars[slot] = i;
                            value = body.eval(f);
                            if (loopEnd(f)) {
                                break;
                            }
                        }
                        return value;
                    };
                }
                pos = save;
            }
            Node init = at(";") ? f -> 0 : statement();
            expect(";");
            Node condition = at(";") ? f -> 1 : expression();
            expect(";");
            Node step = at(")") ? f -> 0 : statement();
            expect(")");
            Node body = block();
            lastWasBlock = true;
            return f -> {
                double value = 0;
                for (init.eval(f); truth(condition.eval(f)); step.eval(f)) {
                    count(f);
                    value = body.eval(f);
                    if (loopEnd(f)) {
                        break;
                    }
                }
                return value;
            };
        }

        // ------------------------------------------------------ expressions

        private Node expression() {
            return assignment();
        }

        private Node assignment() {
            if (peek().kind == Kind.NAME && pos + 1 < tokens.size()) {
                Token op = tokens.get(pos + 1);
                if (op.kind == Kind.OP && isAssign(op.text)) {
                    String name = next().text;
                    if (Functions.CONSTANTS.containsKey(name)) {
                        throw error("'" + name + "' is a constant", op.at);
                    }
                    next();
                    Node value = assignment();
                    int slot = slot(name);
                    return switch (op.text) {
                        case "=" -> f -> f.vars[slot] = value.eval(f);
                        case "+=" -> f -> f.vars[slot] += value.eval(f);
                        case "-=" -> f -> f.vars[slot] -= value.eval(f);
                        case "*=" -> f -> f.vars[slot] *= value.eval(f);
                        case "/=" -> f -> f.vars[slot] /= value.eval(f);
                        case "%=" -> f -> f.vars[slot] %= value.eval(f);
                        default -> f -> f.vars[slot] = Math.pow(f.vars[slot], value.eval(f));
                    };
                }
            }
            return ternary();
        }

        private static boolean isAssign(String op) {
            return op.equals("=") || op.equals("+=") || op.equals("-=") || op.equals("*=") || op.equals("/=")
                    || op.equals("%=") || op.equals("^=") || op.equals("**=");
        }

        private Node ternary() {
            Node condition = or();
            if (accept("?")) {
                Node then = assignment();
                expect(":");
                Node otherwise = assignment();
                return f -> truth(condition.eval(f)) ? then.eval(f) : otherwise.eval(f);
            }
            return condition;
        }

        private Node or() {
            Node left = and();
            while (accept("||")) {
                Node a = left;
                Node b = and();
                left = f -> bool(truth(a.eval(f)) || truth(b.eval(f)));
            }
            return left;
        }

        private Node and() {
            Node left = equality();
            while (accept("&&")) {
                Node a = left;
                Node b = equality();
                left = f -> bool(truth(a.eval(f)) && truth(b.eval(f)));
            }
            return left;
        }

        private Node equality() {
            Node left = comparison();
            while (true) {
                Node a = left;
                if (accept("==")) {
                    Node b = comparison();
                    left = fold(a, b, f -> bool(a.eval(f) == b.eval(f)));
                } else if (accept("!=")) {
                    Node b = comparison();
                    left = fold(a, b, f -> bool(a.eval(f) != b.eval(f)));
                } else if (accept("~=")) {
                    Node b = comparison();
                    left = fold(a, b, f -> bool(Math.abs(a.eval(f) - b.eval(f)) < 1e-7));
                } else {
                    return left;
                }
            }
        }

        private Node comparison() {
            Node left = shift();
            while (true) {
                Node a = left;
                if (accept("<=")) {
                    Node b = shift();
                    left = fold(a, b, f -> bool(a.eval(f) <= b.eval(f)));
                } else if (accept(">=")) {
                    Node b = shift();
                    left = fold(a, b, f -> bool(a.eval(f) >= b.eval(f)));
                } else if (accept("<")) {
                    Node b = shift();
                    left = fold(a, b, f -> bool(a.eval(f) < b.eval(f)));
                } else if (accept(">")) {
                    Node b = shift();
                    left = fold(a, b, f -> bool(a.eval(f) > b.eval(f)));
                } else {
                    return left;
                }
            }
        }

        private Node shift() {
            Node left = additive();
            while (true) {
                Node a = left;
                if (accept("<<")) {
                    Node b = additive();
                    left = fold(a, b, f -> (double) ((long) a.eval(f) << (long) b.eval(f)));
                } else if (accept(">>")) {
                    Node b = additive();
                    left = fold(a, b, f -> (double) ((long) a.eval(f) >> (long) b.eval(f)));
                } else {
                    return left;
                }
            }
        }

        private Node additive() {
            Node left = multiplicative();
            while (true) {
                Node a = left;
                if (accept("+")) {
                    Node b = multiplicative();
                    left = fold(a, b, f -> a.eval(f) + b.eval(f));
                } else if (accept("-")) {
                    Node b = multiplicative();
                    left = fold(a, b, f -> a.eval(f) - b.eval(f));
                } else {
                    return left;
                }
            }
        }

        private Node multiplicative() {
            Node left = unary();
            while (true) {
                Node a = left;
                if (accept("*")) {
                    Node b = unary();
                    left = fold(a, b, f -> a.eval(f) * b.eval(f));
                } else if (accept("/")) {
                    Node b = unary();
                    left = fold(a, b, f -> a.eval(f) / b.eval(f));
                } else if (accept("%")) {
                    Node b = unary();
                    left = fold(a, b, f -> a.eval(f) % b.eval(f));
                } else {
                    return left;
                }
            }
        }

        private Node unary() {
            if (accept("-")) {
                Node a = unary();
                return fold(a, a, f -> -a.eval(f));
            }
            if (accept("+")) {
                return unary();
            }
            if (accept("!")) {
                Node a = unary();
                return fold(a, a, f -> bool(!truth(a.eval(f))));
            }
            if (accept("~")) {
                Node a = unary();
                return fold(a, a, f -> (double) ~(long) a.eval(f));
            }
            if (at("++") || at("--")) {
                boolean up = next().text.equals("++");
                Token name = next();
                if (name.kind != Kind.NAME) {
                    throw error("a variable is expected after " + (up ? "++" : "--"), name.at);
                }
                int slot = slot(name.text);
                return up ? f -> ++f.vars[slot] : f -> --f.vars[slot];
            }
            return power();
        }

        private Node power() {
            Node base = postfix();
            if (accept("^") || accept("**")) {
                Node exponent = unary();
                Node a = base;
                return fold(a, exponent, f -> Math.pow(a.eval(f), exponent.eval(f)));
            }
            return base;
        }

        private Node postfix() {
            Token t = peek();
            if (t.kind == Kind.NAME && pos + 1 < tokens.size()) {
                Token after = tokens.get(pos + 1);
                if (after.kind == Kind.OP && (after.text.equals("++") || after.text.equals("--"))
                        && !Functions.CONSTANTS.containsKey(t.text)) {
                    pos += 2;
                    int slot = slot(t.text);
                    return after.text.equals("++") ? f -> f.vars[slot]++ : f -> f.vars[slot]--;
                }
            }
            return primary();
        }

        private Node primary() {
            Token t = next();
            if (t.kind == Kind.NUMBER) {
                double v = t.number;
                return new Constant(v);
            }
            if (t.kind == Kind.OP && t.text.equals("(")) {
                Node inner = expression();
                expect(")");
                return inner;
            }
            if (t.kind == Kind.NAME) {
                if (accept("(")) {
                    List<Node> args = new ArrayList<>();
                    if (!accept(")")) {
                        do {
                            args.add(expression());
                        } while (accept(","));
                        expect(")");
                    }
                    try {
                        return Functions.call(t.text, args.toArray(new Node[0]));
                    } catch (IllegalArgumentException e) {
                        throw error(e.getMessage(), t.at);
                    }
                }
                Double constant = Functions.CONSTANTS.get(t.text);
                if (constant != null && !slots.containsKey(t.text)) {
                    return new Constant(constant);
                }
                if (isKeyword(t.text)) {
                    throw error("'" + t.text + "' cannot be used as a value", t.at);
                }
                int slot = slot(t.text);
                return f -> f.vars[slot];
            }
            throw error(t.kind == Kind.END ? "the expression ends too early" : "unexpected '" + t.text + "'", t.at);
        }

        private static boolean isKeyword(String name) {
            return switch (name) {
                case "if", "else", "while", "do", "for", "break", "continue", "return" -> true;
                default -> false;
            };
        }

        /** Computes right away what depends on no variable. */
        private static Node fold(Node a, Node b, Node op) {
            if (a instanceof Constant && b instanceof Constant) {
                return new Constant(op.eval(null));
            }
            return op;
        }
    }

    record Constant(double value) implements Node {
        @Override
        public double eval(Frame f) {
            return value;
        }
    }
}
