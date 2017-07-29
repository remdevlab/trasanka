package org.remdev.lang.trasanka;

import static org.remdev.lang.trasanka.Constants.EOF;

/**
 * Created by satalin on 7/28/17.
 */
public enum Lexeme {
    LEX_EOF(EOF),
    LEX_ASM(0),
    LEX_IF(1),
    LEX_ELSE(2),
    LEX_ELIF(3),
    LEX_WHILE(4),
    LEX_BREAK(5),
    LEX_CONTINUE(6),
    LEX_RETURN(7),
    LEX_VAR(8),
    LEX_KEYWORD_COUNT(9),
    LEX_BLOCK_END(10),
    LEX_CHAR(11),
    LEX_STRING(12),
    LEX_NUMBER(13),
    LEX_IDENT(14),
    LEX_ASM_LINE(15),
    LEX_LE(16),
    LEX_GE(17),
    LEX_EQ(18),
    LEX_NE(19),
    LEX_SIZE(20);

    public final int value;

    Lexeme(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

}
