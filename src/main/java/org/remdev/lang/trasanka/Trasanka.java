package org.remdev.lang.trasanka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.remdev.lang.trasanka.Constants.EOF;
import static org.remdev.lang.trasanka.Lexeme.*;


/**
 * Created by satalin on 7/27/17.
 */
public class Trasanka {
    private static Logger LOGGER = LoggerFactory.getLogger(Trasanka.class);
    private final BufferedReader source;
    private int lineNumber = 1;
    private int character;
    private int cursorPos;
    private int brackets = 0;
    private int block = 0;
    private int indent = 0;
    private int newline = 1;
    private boolean asmActive = false;
    private int localCount;
    private boolean negNumber;

    private int lexeme;
    private String token;
    private BigInteger number;

    // code generation
    private static final String[] call_regs = {"rdi", "rsi", "rdx", "rcx", "r8", "r9"};
    private static final String[] regs = {"r8", "r9", "r11", "rax"};

    private static int cacheSize = regs.length;

    private int[] cache = new int[cacheSize];

    private int stackSize;
    private int label = 0;
    private int[] while_labels = new int[256];
    private int while_level = -1;

    // symbol table
    static class Variable {
        public String name;
        public int offset;
    }

    private Variable[] locals = new Variable[1024];

    private final String keywords[] = {
            "asm",
            "if",
            "else",
            "elif",
            "while",
            "break",
            "continue",
            "return",
            "var",
            "null", //TODO
            "block end",
            "character",
            "string",
            "number",
            "identifier",
    };

    public Trasanka(BufferedReader source) {
        this.source = source;
    }

    public char getCharacter() {
        return (char) character;
    }

    public static void main(String[] args) {
        Path currentRelativePath = Paths.get("");
        log("текущий каталог: " + currentRelativePath.toAbsolutePath().toString());
        log("кол-во аргументов: " + args.length);
        if (args.length < 1 || args.length > 2) {
            print(String.format("использовать: %s <источник> [результат]\n", args[0]));
            return;
        }

        File srcFile = new File(args[0]);
        boolean accessable = srcFile.exists() && srcFile.canRead();
        if (!accessable) {
            errorAndExit("Ошибка открытия исходного файла");
        }

        final File destFile;

        if (args.length == 2) {
            destFile = new File(args[1]);
            accessable = destFile.exists() && destFile.canWrite();
            if (!accessable) {
                errorAndExit("Ошибка открытия конечного файла");
            }
        } else {
            destFile = new File("результат.s");
        }
        BufferedReader reader = null;
        try {
            System.setOut(new PrintStream(destFile));
            reader = new BufferedReader(new FileReader(srcFile));
            new Trasanka(reader).compile();
        } catch (Exception e) {
            errorAndExit(e.toString(), e);
        } finally {
            cleanup(reader);
        }
    }

    private static void cleanup(Closeable stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (Throwable th) {
            log(th.toString(), th);
        }
    }

    public static void output(String messages, Object... args) {
        output(String.format(messages, args));
    }

    public static void output(String messages) {
        System.out.print(messages);
    }

    public static void print(String message) {
        System.out.print(message);
    }

    public void error(String message) {
        errorAndExit("<" + lineNumber + "> : " + message);
    }


    private static void errorAndExit(String message, Exception e) {
        System.err.print(message + "\n");
        Thread.dumpStack();
        throw new RuntimeException(message, e);
    }

    public static void errorAndExit(String message) {
        System.err.print(message + "\n");
        Thread.dumpStack();
        throw new IllegalStateException(message);
    }

    public static void log(String message) {
        LOGGER.debug(message);
    }

    public static void log(String message, Throwable th) {
        LOGGER.debug(message, th);
    }


    private void compile() throws Exception {
        readChar();
        readLexeme();

        output("\t.intel_syntax noprefix\n");
        output("\t.text\n");

        while (lexeme != LEX_EOF.getValue()) {

            // global variables
            while (lexeme == LEX_VAR.getValue()) {
                readLexeme();
                expect(LEX_IDENT);
                output(String.format("\t.comm %s, 8, 8\n", token));
                while (lexeme == ',') {
                    readLexeme();
                    expect(LEX_IDENT);
                    output(String.format("\t.comm %s, 8, 8\n", token));
                }
                while (lexeme == ';') readLexeme();
            }

            expect(LEX_IDENT);
            output(String.format("\t.globl %s\n", token));
            output(String.format("%s:\n", token));
            output("\tpush rbp\n");
            output("\tmov rbp, rsp\n");

            int frame = 0;
            localCount = 0;

            // parameter list
            int params = 0;
            expect('(');
            if (lexeme == LEX_IDENT.getValue()) {
                params++;
                expect(LEX_IDENT);
                frame += 8;
                addLocal(frame);
                while (lexeme == ',') {
                    readLexeme();
                    params++;
                    if (params > 6) error("слишком много параметров");
                    expect(LEX_IDENT);
                    frame += 8;
                    addLocal(frame);
                }
            }
            expect(')');
            expect(':');

            // local variables
            while (lexeme == ';') {
                readLexeme();
            }
            while (lexeme == LEX_VAR.getValue()) {
                readLexeme();
                expect(LEX_IDENT);
                frame += 8;
                addLocal(frame);
                while (lexeme == ',') {
                    readLexeme();
                    expect(LEX_IDENT);
                    frame += 8;
                    addLocal(frame);
                }
                while (lexeme == ';') {
                    readLexeme();
                }
            }

            if (frame > 0) output(String.format("\tsub rsp, %d\n", frame));
            for (int i = 0; i < params; i++) {
                output(String.format("\tmov QWORD PTR [rbp - %d], %s\n", i * 8 + 8, call_regs[i]));
            }

            initCache();
            statementList();
            output("\tleave\n");
            output("\tret\n");
            expect(LEX_BLOCK_END);
        }
    }

    private void statementList() throws IOException {
        while (isStmtBeginning()) {
            statement();
        }
    }

    private String regname(int i) {
        return regs[cache[i]];
    }

    private void statement() throws IOException {
        if (lexeme == LEX_ASM.getValue()) {
            readLexeme();
            asmActive = true;
            newline = 1;
            expect(':');
            while (lexeme == LEX_ASM_LINE.getValue()) {
                if (lexeme == LEX_ASM_LINE.getValue()) {
                    output(String.format("\t%s\n", token));
                }
                readLexeme();
            }
            expect(LEX_BLOCK_END);
        } else if (lexeme == LEX_IF.getValue()) {
            readLexeme();
            expression();
            expect(':');
            int l_end = label++;
            int l_next = label++;
            boolean end = false;
            output(String.format("\ttest %s, %s\n", regname(0), regname(0)));
            output(String.format("\tjz .L%d\n", l_next));
            initCache();
            statement_list();
            expect(LEX_BLOCK_END);
            if (lexeme == LEX_ELIF.getValue() || lexeme == LEX_ELSE.getValue()) {
                output(String.format("\tjmp .L%d\n", l_end));
                end = true;
            }
            output(String.format(".L%d:\n", l_next));
            while (lexeme == LEX_ELIF.getValue()) {
                readLexeme();
                expression();
                expect(':');
                l_next = label++;
                output(String.format("\ttest %s, %s\n", regname(0), regname(0)));
                output(String.format("\tjz .L%d\n", l_next));
                initCache();
                statement_list();
                expect(LEX_BLOCK_END);
                if (lexeme == LEX_ELIF.getValue() || lexeme == LEX_ELSE.getValue()) {
                    output(String.format("\tjmp .L%d\n", l_end));
                }
                output(String.format(".L%d:\n", l_next));
            }
            if (lexeme == LEX_ELSE.getValue()) {
                readLexeme();
                expect(':');
                initCache();
                statement_list();
                expect(LEX_BLOCK_END);
            }
            if (end) {
                output(String.format(".L%d:\n", l_end));
            }
        } else if (lexeme == LEX_WHILE.getValue()) {
            readLexeme();
            while_level++;
            if (while_level == 256) {
                error("лимит вложенности для While исчерпан");
            }
            while_labels[while_level] = label;
            label += 2;
            output(String.format(".L%d:\n", while_labels[while_level]));
            expression();
            expect(':');
            output(String.format("\ttest %s, %s\n", regname(0), regname(0)));
            output(String.format("\tjz .L%d\n", while_labels[while_level] + 1));
            initCache();
            statement_list();
            expect(LEX_BLOCK_END);
            output(String.format("\tjmp .L%d\n", while_labels[while_level]));
            output(String.format(".L%d:\n", while_labels[while_level] + 1));
            while_level--;
        } else if (lexeme == LEX_BREAK.getValue()) {
            readLexeme();
            if (while_level < 0) {
                error("break without while");
            }
            output(String.format("\tjmp .L%d\n", while_labels[while_level] + 1));
        } else if (lexeme == LEX_CONTINUE.getValue()) {
            readLexeme();
            if (while_level < 0) {
                error("continue without while");
            }
            output(String.format("\tjmp .L%d\n", while_labels[while_level]));
        } else if (lexeme == LEX_RETURN.getValue()) {
            readLexeme();
            if (is_expr_beginning()) {
                expression();
                if (!regname(0).equals("rax")) {
                    output(String.format("\tmov rax, %s\n", regname(0)));
                }
                pop();
            }
            output("\tleave\n");
            output("\tret\n");
        } else if (is_expr_beginning()) {
            expression();
            pop();
        } else expect(';');
    }

    private void expression() throws IOException {
        expr_level_four();
        while (lexeme == '|') {
            readLexeme();
            expr_level_four();
            output(String.format("\tor %s, %s\n", regname(1), regname(0)));
            pop();
        }
    }

    private void expr_level_four() throws IOException {
        expr_level_three();
        while (lexeme == '&') {
            readLexeme();
            expr_level_three();
            output(String.format("\tand %s, %s\n", regname(1), regname(0)));
            pop();
        }
    }

    private void expr_level_three() throws IOException {
        expr_level_two();
        String comp;
        switch (lexeme) {
            case '<':
                comp = "l";
                break;
            case '>':
                comp = "g";
                break;
            //case LEX_LE.getValue():
            case 16:
                comp = "le";
                break;
            //case LEX_GE.getValue():
            case 17:
                comp = "ge";
                break;
            //case LEX_EQ.getValue():
            case 18:
                comp = "e";
                break;
            //case LEX_NE.getValue():
            case 19:
                comp = "ne";
                break;
            default:
                return;
        }
        readLexeme();
        expr_level_two();
        output("\tcmp %s, %s\n", regname(1), regname(0));
        output("\tset%s cl\n", comp);
        output("\tmovzx %s, cl\n", regname(1));
        pop();
    }

    private void expr_level_two() throws IOException {
        expr_level_one();
        while ("+-".contains(String.valueOf((char) lexeme))) {
            if (lexeme == '+') {
                readLexeme();
                expr_level_one();
                output(String.format("\tadd %s, %s\n", regname(1), regname(0)));
                pop();
            } else if (lexeme == '-') {
                readLexeme();
                expr_level_one();
                output(String.format("\tsub %s, %s\n", regname(1), regname(0)));
                pop();
            }
        }
    }

    private void expr_level_one() throws IOException {
        expr_level_zero();
        while ("*%/".contains(String.valueOf((char) lexeme))) {
            if (lexeme == '*') {
                readLexeme();
                expr_level_zero();
                output(String.format("\timul %s, %s\n", regname(1), regname(0)));
                pop();
            } else if (lexeme == '%') {
                error("TODO");
            } else if (lexeme == '/') {
                error("TODO");
            }
        }
    }

    private void expr_level_zero() throws IOException {
        if (lexeme == '!') {
            readLexeme();
            expr_level_zero();
            output("\ttest %s, %s\n", regname(0), regname(0));
            output("\tsetz cl\n");
            output("\tmovzx %s, cl\n", regname(0));
            return;
        }
        if (lexeme == '-') {
            if (!negNumber) {
                readLexeme();
                expr_level_zero();
                output("\tneg %s\n", regname(0));
                return;
            }
            readLexeme();
            push();
            output("\tmov %s, %d\n", regname(0), number.negate());//TODO negotive, was ld
            readLexeme();
        } else if (lexeme == LEX_NUMBER.getValue()) {
            push();
            output("\tmov %s, %d\n", regname(0), number); //todo was ld
            readLexeme();
        } else if (lexeme == LEX_CHAR.getValue()) {
            push();
            output("\tmov %s, %s\n", regname(0), token);
            readLexeme();
        } else if (lexeme == '(') {
            readLexeme();
            expression();
            expect(')');
        } else if (lexeme == LEX_IDENT.getValue()) {
            String name = token;
            Variable v = lookup_local();
            readLexeme();
            if (lexeme == '(') {    // function call
                // save used regs on stack
                int i = stackSize;
                if (i > cacheSize) {
                    i = cacheSize;
                }
                while (i-- > 0) {
                    output("\tpush %s\n", regname(i));
                }

                int old_size = stackSize;
                stackSize = 0;

                // expr list
                int args = 0;
                readLexeme();
                if (is_expr_beginning()) {
                    args++;
                    expression();
                    output("\tpush %s\n", regname(0));
                    pop();
                    while (lexeme == ',') {
                        readLexeme();
                        args++;
                        if (args > 6) error("слишком много аргументов");
                        expression();
                        output("\tpush %s\n", regname(0));
                        pop();
                    }
                }
                expect(')');

                // set-up registers
                for (int j = args - 1; j >= 0; j--) {
                    output("\tpop %s\n", call_regs[j]);
                }

                // call
                output("\txor rax, rax\n");
                output("\tcall %s\n", name);

                initCache();
                push();
                stackSize = old_size + 1;
                int m = stackSize;
                if (m > cacheSize) m = cacheSize;
                for (i = 1; i < m; i++) {
                    output("\tpop %s\n", regname(i));
                }
            } else if (lexeme == '=') {
                readLexeme();
                expression();
                if (v == null) {
                    output("\tmov %s, %s\n", name, regname(0));
                } else {
                    output("\tmov QWORD PTR [rbp - %d], %s\n", v.offset, regname(0));
                }
            } else {
                push();
                if (v == null) {
                    output("\tmov %s, %s\n", regname(0), name);
                } else {
                    output("\tmov %s, QWORD PTR [rbp - %d]\n", regname(0), v.offset);
                }
            }
        } else if (lexeme == '@') {
            // dereference
            error("not implementet yet");
        } else if (lexeme == LEX_STRING.getValue()) {
            push();
            output("\t.section .rodata\n");
            output("LC%d:\n", label);
            output("\t.string %s\n", token);
            output("\t.text\n");
            output("\tmov %s, OFFSET LC%d\n", regname(0), label);
            label++;
            readLexeme();
        } else {
            error("bad expression");
        }

        while (lexeme == '[') {
            readLexeme();
            expression();
            expect(']');
            if (lexeme == '=') {
                readLexeme();
                expression();
                output("\tmov QWORD PTR [%s + %s * 8], %s\n", regname(2), regname(1), regname(0));
                int tmp = cache[2];
                cache[2] = cache[0];
                cache[0] = tmp;
                pop();
                pop();
                return;
            }
            output("\tmov %s, QWORD PTR [%s + %s * 8]\n", regname(1), regname(1), regname(0));
            pop();
        }
        if (lexeme == '{') {
            readLexeme();
            expression();
            expect('}');
            if (lexeme == '=') {
                readLexeme();
                expression();
                output("\tmov rcx, %s\n", regname(0));
                output("\tmov BYTE PTR [%s + %s], cl\n", regname(2), regname(1));
                int tmp = cache[2];
                cache[2] = cache[0];
                cache[0] = tmp;
                pop();
                pop();
                return;
            }
            output("\tmov cl, BYTE PTR [%s + %s]\n", regname(1), regname(0));
            output("\tmovzx %s, cl\n", regname(1));
            pop();
        }
    }

    private Variable lookup_local() {
        for (int i = 0; i < localCount; i++) {
            if (token.equals(locals[i].name)) {
                return locals[i];
            }
        }
        return null;
    }

    private void push() {
        int i = cacheSize - 1;
        int tmp = cache[i];
        if (stackSize >= cacheSize) {
            output("\tpush %s\n", regs[tmp]);
        }
        while (i > 0) {
            cache[i] = cache[i - 1];
            i--;
        }
        cache[0] = tmp;
        stackSize++;
    }

    void pop() {
        stackSize--;
        if (stackSize == 0) initCache();
        else {
            int i = 0;
            int tmp = cache[0];
            while (i < cacheSize - 1) {
                cache[i] = cache[i + 1];
                i++;
            }
            cache[i] = tmp;
            if (stackSize >= cacheSize) output(String.format("\tpop %s\n", regs[i]));
        }
    }

    void statement_list() throws IOException {
        while (isStmtBeginning()) {
            statement();
        }
    }


    private boolean isStmtBeginning() {
        int[] lexemes = {LEX_ASM.getValue(), LEX_IF.getValue(), LEX_WHILE.getValue(), LEX_BREAK.getValue(), LEX_CONTINUE.getValue(), LEX_RETURN.getValue(), ';'};
        for (int i = 0; i < lexemes.length; i++) {
            if (lexeme == lexemes[i]) return true;
        }
        return is_expr_beginning();
    }

    private boolean is_expr_beginning() {
        int[] lexemes = {'-', '!', '(', LEX_NUMBER.getValue(), LEX_CHAR.getValue(), LEX_STRING.getValue(), LEX_IDENT.getValue()};
        for (int i = 0; i < lexemes.length; i++) {
            if (lexeme == lexemes[i]) return true;
        }
        return false;
    }

    private void initCache() {
        for (int i = 0; i < cacheSize; i++) {
            cache[i] = i;
        }
        stackSize = 0;
    }

    private void addLocal(int frame) {
        for (int i = 0; i < 1024; i++) {
            if (i == localCount) {
                locals[i] = new Variable();
                locals[i].name = token;
                locals[i].offset = frame;
                localCount++;
                return;
            }
            if (token.equals(locals[i].name)) {
                error("множественное объявление локальной переменной");
            }
        }
        error("слишком много переменных");
    }

    private void expect(Lexeme lex) throws IOException {
        expect(lex.getValue());
    }

    private void expect(int lex) throws IOException {
        if (lexeme != lex) {
            if (lex < LEX_SIZE.getValue()) {
                error(lineNumber + ":" + lex + ":" + lexeme);
                if (lex > 0 && lexeme > 0) {
                    error(String.format("< %d > : %s Ожидался но найден %s", lineNumber, keywords[lex], keywords[lexeme]));
                }
            } else {
                log(lineNumber + ":" + lex + ":" + lexeme);
                error(lineNumber + ":" + lex + ":" + lexeme);
                error(String.format("< %d > : <%c> Ожидался но найден %d", lineNumber, lex, lexeme));
            }
        }
        readLexeme();
    }

    private int readChar() throws IOException {
        int c = character;
        character = source.read();
        cursorPos++;
        if (character == '\n') {
            lineNumber++;
            cursorPos = 0;
        }
        return c;
    }

    private boolean isSpace(char character) {
        return Character.isWhitespace(character);
    }

    private void readLexeme() throws IOException {
        lexeme = scan();
    }

    private int scan() throws IOException {
        boolean flag = true;
        while (flag) {
            flag = false;
            while (Character.isWhitespace(character)) {
                if (newline > 0) {
                    if (character == ' ') indent++;
                    if (character == '\t') indent = (indent & ~3) + 4;
                }
                if (character == '\n') {
                    indent = 0;
                    int n = newline;
                    newline = 1;
                    if (n == 0 && brackets == 0) {
                        return ';';
                    }
                }
                readChar();
            }

            // игнорируем комментарий
            if (character == '#') {
                while (character != '\n') {
                    readChar();
                }
                flag = true;
            }
        }
        // обзац
        if (!(brackets > 0)) {
            if (indent > block) error("invalid indentation");
            if (indent < block) {
                asmActive = false;
                block -= 4;
                return LEX_BLOCK_END.getValue();
            }
        }

        // ассемблерная линия
        if (asmActive) {
            StringBuilder sb = new StringBuilder(1024);
            while (character != '\n') {
                sb.append((char) readChar());
            }
            //sb.append('\0');
            token = sb.toString();
            return LEX_ASM_LINE.getValue();
        }

        newline = 0;
        // односимвольный токен
        if ("-+*/%&|~!=<>;:()[],@{}".contains(String.valueOf((char) character))) {
            int c = readChar();
            if (c == ':') {    // новый блок
                block += 4;
                indent += 4;
            } else if ("<>!=".contains(String.valueOf((char) c)) && character == '=') {
                readChar();
                switch (c) {
                    case '<':
                        return LEX_LE.getValue();
                    case '>':
                        return LEX_GE.getValue();
                    case '=':
                        return LEX_EQ.getValue();
                    case '!':
                        return LEX_NE.getValue();
                }
            } else if (c == '(' || c == '[') {
                brackets++;
            } else if (c == ')' || c == ']') {
                brackets--;
            }
            if (Character.isDigit(character)) {
                negNumber = (c == '-');
            }
            return c;
        }
        // символ
        if (character == '\'') {
            readChar();
            StringBuilder sb = new StringBuilder();
            sb.append('\'');
            int i = 1;
            if (character == '\\') {
                i++;
                sb.append((char) readChar());
            }
            i += 2;
            sb.append((char) readChar());
            sb.append('\'');
            // sb.append('\0');
            token = sb.toString();
            if (readChar() != '\'') {
                error("плохой символ");
            }
            return LEX_CHAR.getValue();
        }

        // строка
        if (character == '"') {
            int i = 0;
            StringBuilder sb = new StringBuilder();
            do {
                if (character == '\\') {
                    i++;
                    sb.append((char) readChar());
                }
                i++;
                sb.append((char) readChar());
                if (i > 1020) {
                    error("строка слишком длинная");
                }
            } while (character != '"');
            sb.append((char) readChar());
            //sb.append('\0');
            token = sb.toString();
            return LEX_STRING.getValue();
        }

        // число
        if (Character.isDigit(character)) {
            int i = 0;
            StringBuilder sb = new StringBuilder();
            do {
                sb.append((char) readChar());
                i++;
                if (i > 20) {
                    error("число слишком большое");
                }
            } while (Character.isDigit(character));
            //sb.append('\0');
            token = sb.toString();
            number = new BigInteger(token);
            return LEX_NUMBER.getValue();
        }

        // лючевые слова и идентификаторы
        if (Character.isAlphabetic(character) || character == '_') {
            int i = 0;
            StringBuilder sb = new StringBuilder();
            do {
                i++;
                sb.append((char) readChar());
                if (i > 62) error("идентификатор слишком длинный");
            } while (Character.isLetterOrDigit(character) || character == '_');
            token = sb/*.append('\0')*/.toString();
            // проверка на ключевые слова
            for (i = 0; i < LEX_KEYWORD_COUNT.getValue(); i++) {
                if (token.equals(keywords[i])) {
                    return i;
                }
            }
            return LEX_IDENT.getValue();
        }

        if (character != EOF) {
            error("неизвестный символ : \"" + (char) character + "\"");
        }
        if (block > 0) {
            block -= 4;
            return LEX_BLOCK_END.getValue();
        }
        return LEX_EOF.getValue();
    }
}
