package uz.horecaos.platform.payments.infrastructure.payme;

/**
 * Every error this adapter can return, written once (ADR 0013).
 *
 * <p>Gathered here rather than constructed at each throw site so that the wording
 * a payer reads is reviewable in one screen, in all three languages, by somebody
 * who does not read Java. The Russian text follows Payme's own PHP template where
 * that template has a phrase for the case.
 *
 * <p>Public only so that the controller in {@code payments.web.payme} can raise the
 * four protocol errors it decides on its own account — an unknown cashbox, a
 * non-POST arrival, a body that never parsed, and an envelope that parsed and is
 * structurally wrong. Those are decided before any adapter is involved, and a
 * second copy of the wording in the web layer is how two error messages for one
 * condition start disagreeing.
 */
public final class PaymeErrors {

    private PaymeErrors() {
    }

    public static PaymeRpcException insufficientPrivilege() {
        return new PaymeRpcException(PaymeErrorCode.INSUFFICIENT_PRIVILEGE, new PaymeMessage(
                "Недостаточно привилегий для выполнения метода.",
                "Metodni bajarish uchun huquqlar yetarli emas.",
                "Insufficient privilege to perform this method."));
    }

    /**
     * The request did not arrive by POST.
     *
     * <p>Documented, and returned by neither of Payme's own templates: both map
     * their endpoint to POST alone and leave the framework to answer 405, which
     * Payme reads as {@code -32400} — losing the difference between "you used the
     * wrong verb" and "the merchant's database is down".
     */
    public static PaymeRpcException methodNotPost() {
        return new PaymeRpcException(PaymeErrorCode.METHOD_NOT_POST, new PaymeMessage(
                "Запрос должен быть отправлен методом POST.",
                "So'rov POST metodi bilan yuborilishi kerak.",
                "The request must be sent by POST."));
    }

    public static PaymeRpcException parseError() {
        return new PaymeRpcException(PaymeErrorCode.PARSE_ERROR, new PaymeMessage(
                "Не удалось разобрать JSON.",
                "JSON o'qib bo'lmadi.",
                "Could not parse JSON."));
    }

    public static PaymeRpcException invalidRequest(String detail) {
        return new PaymeRpcException(PaymeErrorCode.INVALID_REQUEST, new PaymeMessage(
                "Неверный запрос: " + detail,
                "Noto'g'ri so'rov: " + detail,
                "Invalid request: " + detail));
    }

    /** The method name goes in {@code data}, which is what the docs specify for -32601. */
    public static PaymeRpcException methodNotFound(String method) {
        return new PaymeRpcException(PaymeErrorCode.METHOD_NOT_FOUND, new PaymeMessage(
                "Запрошенный метод не найден.",
                "So'ralgan metod topilmadi.",
                "Requested method was not found."),
                method);
    }

    public static PaymeRpcException internalError() {
        return new PaymeRpcException(PaymeErrorCode.INTERNAL_ERROR, new PaymeMessage(
                "Внутренняя ошибка сервиса.",
                "Xizmatning ichki xatosi.",
                "Internal service error."));
    }

    /**
     * The order behind {@code account.order_id} does not exist, or is not one this
     * cashbox may be paid for.
     *
     * <p>One message for both, deliberately. Distinguishing "no such order" from
     * "that order belongs to another cashbox" would tell an unauthenticated caller
     * which order references are real, and {@code CheckPerformTransaction} is
     * reachable from the checkout page by anyone.
     */
    static PaymeRpcException orderNotFound() {
        return new PaymeRpcException(PaymeErrorCode.ACCOUNT_RANGE_FIRST, new PaymeMessage(
                "Неверный код заказа.",
                "Harid kodida xatolik.",
                "Incorrect order code."),
                PaymeAccount.ORDER_FIELD);
    }

    static PaymeRpcException accountFieldMissing() {
        return new PaymeRpcException(PaymeErrorCode.ACCOUNT_RANGE_FIRST, new PaymeMessage(
                "Не указан код заказа.",
                "Harid kodi ko'rsatilmagan.",
                "The order code is missing."),
                PaymeAccount.ORDER_FIELD);
    }

    static PaymeRpcException wrongAmount() {
        return new PaymeRpcException(PaymeErrorCode.WRONG_AMOUNT, new PaymeMessage(
                "Неверная сумма.",
                "Noto'g'ri summa.",
                "Incorrect amount."));
    }

    static PaymeRpcException transactionNotFound() {
        return new PaymeRpcException(PaymeErrorCode.TRANSACTION_NOT_FOUND, new PaymeMessage(
                "Транзакция не найдена.",
                "Tranzaksiya topilmadi.",
                "Transaction not found."));
    }

    /**
     * The state machine refuses.
     *
     * <p>This is also the code returned for "the order is already paid", which is
     * the one genuinely disputed mapping in the whole integration: the
     * {@code CheckPerformTransaction} page's error table permits only {@code -31001}
     * and the account range, while Payme's own PHP template returns {@code -31008}
     * and the sandbox tests exactly that code for the same condition on
     * {@code CreateTransaction}. The notes recommend {@code -31008} with a fully
     * localised message so that whichever way Payme's validator reads it, the payer
     * still sees a sensible sentence. Confirm against the sandbox before go-live.
     */
    static PaymeRpcException operationNotPermitted(PaymeMessage localised) {
        return new PaymeRpcException(PaymeErrorCode.OPERATION_NOT_PERMITTED, localised);
    }

    static PaymeRpcException orderAlreadyPaid() {
        return operationNotPermitted(new PaymeMessage(
                "Заказ уже оплачен.",
                "Harid allaqachon to'langan.",
                "The order has already been paid."));
    }

    static PaymeRpcException orderNotPayable() {
        return operationNotPermitted(new PaymeMessage(
                "Заказ не ожидает оплаты.",
                "Harid to'lovni kutmayapti.",
                "The order is not awaiting payment."));
    }

    static PaymeRpcException anotherTransactionIsActive() {
        return operationNotPermitted(new PaymeMessage(
                "По этому заказу уже создана транзакция.",
                "Bu harid uchun tranzaksiya allaqachon yaratilgan.",
                "A transaction already exists for this order."));
    }

    static PaymeRpcException transactionExpired() {
        return operationNotPermitted(new PaymeMessage(
                "Транзакция отменена по таймауту.",
                "Tranzaksiya kutish vaqti tugagani uchun bekor qilindi.",
                "The transaction was cancelled by timeout."));
    }

    static PaymeRpcException transactionStateForbidsIt() {
        return operationNotPermitted(new PaymeMessage(
                "Операция недоступна в текущем состоянии транзакции.",
                "Tranzaksiyaning joriy holatida bu amal mumkin emas.",
                "The operation is not available in the transaction's current state."));
    }

    static PaymeRpcException orderAlreadyDelivered() {
        return new PaymeRpcException(PaymeErrorCode.ORDER_ALREADY_DELIVERED, new PaymeMessage(
                "Заказ выполнен. Товар или услуга переданы покупателю в полном объёме.",
                "Harid bajarilgan. Tovar yoki xizmat xaridorga to'liq topshirilgan.",
                "The order is fulfilled. The goods or service were delivered to the buyer in full."));
    }

    static PaymeRpcException fiscalReceiptNotFound() {
        return new PaymeRpcException(PaymeErrorCode.FISCAL_RECEIPT_NOT_FOUND, new PaymeMessage(
                "Чек с таким id не найден.",
                "Bunday id bilan chek topilmadi.",
                "No receipt with that id was found."));
    }

    static PaymeRpcException fiscalInvalidParameters(String detail) {
        return new PaymeRpcException(PaymeErrorCode.FISCAL_INVALID_PARAMETERS, new PaymeMessage(
                "Неверные параметры: " + detail,
                "Noto'g'ri parametrlar: " + detail,
                "Invalid parameters: " + detail));
    }
}
