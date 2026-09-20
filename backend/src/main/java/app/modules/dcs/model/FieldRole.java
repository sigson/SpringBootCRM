package app.modules.dcs.model;

/**
 * Роли полей — семантика, подсказывающая движку алгоритм расчёта итогов.
 *
 * <p>Порядок ровно тот же, что в 1С:СКД: обычные измерения суммируются, а поля-остатки
 * итожатся не суммированием, а взятием записи, ближайшей к границе периода, в разрезе
 * измерений (см. {@code CompositionProcessor}).
 */
public final class FieldRole {

    private FieldRole() {}

    /** Измерение — обычный разрез группировки. */
    public static final String DIMENSION = "dimension";
    /** Ресурс — агрегируемое числовое поле. */
    public static final String RESOURCE = "resource";
    /** Период — поле даты/времени, задающее ось периодов. */
    public static final String PERIOD = "period";
    /** Дополнительный период. */
    public static final String ADDITIONAL_PERIOD = "additionalPeriod";
    /** Начальный остаток. */
    public static final String BEGIN_BALANCE = "beginBalance";
    /** Конечный остаток. */
    public static final String END_BALANCE = "endBalance";
    /** Счёт. */
    public static final String ACCOUNT = "account";

    public static boolean isBalance(String role) {
        return BEGIN_BALANCE.equals(role) || END_BALANCE.equals(role);
    }
}
