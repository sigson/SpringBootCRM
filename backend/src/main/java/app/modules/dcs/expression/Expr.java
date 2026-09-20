package app.modules.dcs.expression;

import java.util.List;

/**
 * Узлы разобранного выражения языка компоновки.
 *
 * <p>Дерево намеренно маленькое: язык СКД — это литералы, поля, арифметика, сравнения,
 * {@code ВЫБОР … КОНЕЦ} и вызовы функций. Всё остальное (агрегаты, {@code ВычислитьВыражение},
 * {@code Уровень}) — обычные {@link Call}, чья семантика задаётся уже при вычислении:
 * так набор функций расширяется без правок грамматики.
 */
public sealed interface Expr {

    /** Литерал: число, строка, логическое значение или {@code null}. */
    record Lit(Object value) implements Expr {}

    /** Ссылка на поле — {@code Sales.amount}, {@code amount} или имя ресурса. */
    record Field(String path) implements Expr {}

    /** Параметр схемы: {@code &Начало}. */
    record Param(String name) implements Expr {}

    /** Унарная операция: {@code -}, {@code НЕ}. */
    record Unary(String op, Expr operand) implements Expr {}

    /** Бинарная операция: арифметика, сравнение, {@code И}/{@code ИЛИ}, {@code ПОДОБНО}. */
    record Binary(String op, Expr left, Expr right) implements Expr {}

    /** Вызов функции: агрегат, {@code Представление}, {@code ВычислитьВыражение}, … */
    record Call(String name, List<Expr> args) implements Expr {}

    /** {@code ВЫБОР КОГДА … ТОГДА … ИНАЧЕ … КОНЕЦ}. */
    record Case(List<Branch> branches, Expr otherwise) implements Expr {}

    /** Ветка {@code КОГДА when ТОГДА then}. */
    record Branch(Expr when, Expr then) {}

    /** {@code выражение В (a, b, c)} — вынесено из {@link Binary} из-за списка справа. */
    record In(Expr value, List<Expr> options, boolean negated) implements Expr {}

    /** {@code выражение МЕЖДУ a И b}. */
    record Between(Expr value, Expr low, Expr high) implements Expr {}

    /** {@code выражение ЕСТЬ NULL} / {@code ЕСТЬ НЕ NULL}. */
    record IsNull(Expr value, boolean negated) implements Expr {}
}
