package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** v0.26.1 mini tools: the safe formula evaluator. */
class ExprTest {
    private fun e(s: String, vars: Map<String, Double> = emptyMap()) = Expr.eval(s, vars)

    @Test fun precedenceAndAssociativity() {
        assertEquals(14.0, e("2 + 3 * 4")!!, 1e-9)
        assertEquals(20.0, e("(2 + 3) * 4")!!, 1e-9)
        assertEquals(512.0, e("2 ^ 3 ^ 2")!!, 1e-9)          // right-associative
        assertEquals(-4.0, e("-2 ^ 2")!!, 1e-9)               // unary minus looser than ^
        assertEquals(1.0, e("10 - 4 - 5")!!, 1e-9)            // left-associative
        assertEquals(2.5, e("10 / 2 / 2")!!, 1e-9)
        assertEquals(1.0, e("7 % 3")!!, 1e-9)
        assertEquals(1500.0, e("1.5e3")!!, 1e-9)
        assertEquals(6.0, e("--6")!!, 1e-9)
    }

    @Test fun variablesAndFunctions() {
        val v = mapOf("bill" to 450.0, "people" to 3.0, "tip" to 10.0)
        assertEquals(165.0, e("bill*(1+tip/100)/people", v)!!, 1e-9)
        assertEquals(45.0, e("bill*tip/100", v)!!, 1e-9)
        assertEquals(3.0, e("min(people, 5, 9)", v)!!, 1e-9)
        assertEquals(9.0, e("max(1, 9, people)", v)!!, 1e-9)
        assertEquals(3.14, e("round(3.14159, 2)")!!, 1e-9)
        assertEquals(4.0, e("ceil(3.1)")!!, 1e-9); assertEquals(3.0, e("floor(3.9)")!!, 1e-9)
        assertEquals(5.0, e("abs(-5)")!!, 1e-9); assertEquals(4.0, e("sqrt(16)")!!, 1e-9)
        assertEquals(8.0, e("pow(2, 3)")!!, 1e-9)
        assertEquals(1.0, e("people > 2", v)!!, 1e-9); assertEquals(0.0, e("people >= 4", v)!!, 1e-9)
        assertEquals(1.0, e("people == 3", v)!!, 1e-9); assertEquals(1.0, e("people != 4", v)!!, 1e-9)
        assertEquals(50.0, e("if(bill > 400, 50, 0)", v)!!, 1e-9)
        assertEquals(0.0, e("if(bill <= 400, 50, 0)", v)!!, 1e-9)
        // Loan payment: P r / (1 - (1 + r)^-n)
        val pay = e("p*r/(1-(1+r)^-n)", mapOf("p" to 10000.0, "r" to 0.01, "n" to 12.0))!!
        assertEquals(888.49, pay, 0.01)
    }

    @Test fun divideByZeroAndNonFiniteAreBlank() {
        assertNull(e("1/0")); assertNull(e("5 % 0")); assertNull(e("sqrt(-1)"))
        assertNull(e("bill/people", mapOf("bill" to 10.0, "people" to 0.0)))
        assertNull(e("x + 1", mapOf("x" to Double.NaN)))
        assertEquals("", Expr.format(e("1/0")))
    }

    @Test fun noCodeExecution() {
        for (bad in listOf("java.lang.Runtime.getRuntime()", "System.exit(0)", "exec(\"rm -rf /\")", "eval(1)", "a;b", "\"text\"", "x = 3",
            "1 +", "(1 + 2", "max()", "round(1,2,3)", "foo(2)", "1 2", "", "   ", "bill.length", "Math.PI", "\${1}", "\u0000"))
            assertNull(bad, e(bad, mapOf("bill" to 1.0, "a" to 1.0, "b" to 1.0)))
        // Unknown names and functions are reported, not run.
        assertNotNull(Expr.check("foo(2)", emptySet())); assertNotNull(Expr.check("x + 1", emptySet()))
        assertNull(Expr.check("x + 1", setOf("x")))
        // Very long or very deep formulas are refused instead of blowing the stack.
        assertNull(e("1+".repeat(300) + "1"))
        assertNull(e("(".repeat(200) + "1" + ")".repeat(200)))
    }

    @Test fun formatting() {
        assertEquals("K1,234.50", Expr.format(1234.5, 2, "K"))
        assertEquals("-K12.00", Expr.format(-12.0, 2, "K"))
        assertEquals("10%", Expr.format(10.0, 0, suffix = "%"))
        assertEquals("1,000,000", Expr.format(1e6, 0))
        assertEquals("", Expr.format(null))
    }
}
