package uz.horecaos.platform.legal.domain;

import java.util.Map;

/**
 * The lawful, brand-neutral terms a brand serves until it publishes its own
 * (ADR 0067, the owner's 2026-08-30 decision).
 *
 * <p>Names no brand: every reference to the seller is {@code {{brandName}}},
 * interpolated with the actual brand's display name at serve time — the same
 * value the storefront already shows on its own sign-in screen — so the
 * document reads correctly for any tenant rather than for one specific
 * business, the way the legacy content this replaces named a single legacy
 * brand throughout.
 *
 * <p><strong>This is not legal advice and claims no specific jurisdiction's
 * law.</strong> It is generic, general-purpose marketplace wording a tenant's
 * own lawyer is expected to review and normally replace; {@link #forLocale}
 * says so in its own last paragraph, in every language, so a brand that never
 * opens the authoring screen still tells its customers the words are a
 * default rather than implying legal review that never happened.
 *
 * <p>{@link #VERSION} exists because this text can itself change in a later
 * release. An acceptance recorded against {@code "default-v1:en"} must keep
 * meaning exactly today's wording even after a future release edits this
 * class — so a wording change bumps the constant rather than silently
 * reinterpreting every prior default acceptance as having been of the new
 * text.
 */
public final class PlatformDefaultTerms {

    /** Bump this only when the wording below changes; see the class doc. */
    public static final int VERSION = 1;

    private static final String NAME_TOKEN = "{{brandName}}";

    private PlatformDefaultTerms() {}

    /**
     * The default text for one locale, with {@code brandName} interpolated.
     *
     * @param locale one of {@link TermsLocale#tags()}; anything else falls
     *               back to {@link TermsLocale#EN}, the same "never blank"
     *               posture {@code TermsAcceptanceService} applies elsewhere
     * @param brandName never blank in practice — the storefront's own
     *                  {@code AppConfig.brand.displayName} soft-defaults to
     *                  {@code "Storefront"} rather than an empty string
     */
    public static String forLocale(String locale, String brandName) {
        String template = TEMPLATES.get(locale);
        if (template == null) {
            template = EN_TEXT;
        }
        return template.replace(NAME_TOKEN, brandName);
    }

    private static final String EN_TEXT = """
            Terms of Service — {{brandName}}

            These Terms of Service ("Terms") govern your use of the {{brandName}} storefront \
            and your orders placed through it. By creating an account, placing an order, or \
            otherwise using this service, you accept these Terms.

            1. Who these Terms are between
            {{brandName}} sells the products and services offered through this storefront. \
            This document does not create any relationship with the platform that operates \
            the underlying ordering software; your order is with {{brandName}}.

            2. Placing an order
            An order is accepted for delivery or pickup once {{brandName}} confirms it. \
            Prices, available items, delivery zones, delivery fees, minimum order amounts, \
            and estimated timing are those shown at checkout at the time you order, and may \
            change from order to order.

            3. Payment
            You agree to pay the full price shown for your order, including any delivery fee \
            and applicable taxes, using one of the payment methods {{brandName}} offers. \
            {{brandName}} may cancel an unpaid order that is not completed within a \
            reasonable time.

            4. Changes and cancellations
            Once an order has been accepted and preparation has begun, {{brandName}} may be \
            unable to change or cancel it. Contact {{brandName}} as soon as possible if you \
            need to change or cancel an order; a cancellation after preparation has started \
            may not be refundable.

            5. Delivery and pickup
            {{brandName}} will make reasonable efforts to deliver or prepare your order \
            within the estimated time shown, but delays can happen for reasons outside its \
            control, including traffic, weather, and order volume. Please check your order \
            on receipt and raise any issue with {{brandName}} promptly.

            6. Returns, refunds, and complaints
            If an order arrives incomplete, incorrect, or not as described, contact \
            {{brandName}} promptly with details so it can put things right — by \
            replacement, correction, or refund, at {{brandName}}'s discretion and subject to \
            applicable law. Prepared food that has been accepted in good condition generally \
            cannot be returned.

            7. Your account
            You are responsible for keeping your account and its verification method (such \
            as your phone number) accurate and secure, and for activity that happens through \
            your account. Tell {{brandName}} promptly if you believe your account has been \
            used without your permission.

            8. Your personal data
            {{brandName}} processes the personal data you provide (such as your name, phone \
            number, and delivery address) to take and fulfil your orders, to communicate with \
            you about them, and for the other purposes described in its privacy notice. You \
            may ask {{brandName}} about the personal data it holds about you or ask it to be \
            corrected, consistent with applicable law.

            9. Marketing communications
            {{brandName}} may send you messages about your orders. Where it sends you \
            marketing or promotional messages, you may opt out of them at any time without \
            affecting your ability to place orders.

            10. Conduct
            You agree not to misuse this service — including placing fraudulent orders, \
            abusing promotional offers, or interfering with the ordering system — and \
            {{brandName}} may refuse service or suspend an account where it reasonably \
            believes this has happened.

            11. Liability
            To the extent permitted by applicable law, {{brandName}}'s liability for a \
            problem with an order is limited to the price paid for that order. Nothing in \
            these Terms limits any liability that cannot lawfully be limited, or excludes any \
            right you have under applicable consumer-protection law.

            12. Changes to these Terms
            {{brandName}} may update these Terms from time to time, for example to reflect a \
            change in its services or in the law. A new version takes effect when it is \
            published, and does not change what you agreed to for an order already placed. \
            Continuing to use this service after a new version is published means you accept \
            it; where required by law, {{brandName}} will ask for your renewed agreement \
            before you place your next order.

            13. Contact
            Questions about these Terms or an order can be sent to {{brandName}} through the \
            contact details shown on this storefront.

            These Terms are a general default provided by the platform. {{brandName}} may \
            replace them with its own terms at any time.
            """;

    private static final String RU_TEXT = """
            Условия использования — {{brandName}}

            Настоящие Условия использования («Условия») регулируют использование витрины {{brandName}} и заказов, оформленных через неё. \
            Создавая учётную запись, оформляя заказ или иным образом используя сервис, вы принимаете настоящие Условия.

            1. Кто является стороной настоящих Условий
            Товары и услуги, предлагаемые через эту витрину, продаёт {{brandName}}. Настоящий документ не создаёт каких-либо отношений с платформой, на которой работает используемое программное обеспечение для приёма заказов; ваш заказ оформляется с {{brandName}}.

            2. Оформление заказа
            Заказ считается принятым к доставке или самовывозу после подтверждения со стороны {{brandName}}. Цены, доступные позиции, зоны доставки, стоимость доставки, минимальная сумма заказа и ориентировочное время указываются при оформлении заказа и могут отличаться от заказа к заказу.

            3. Оплата
            Вы соглашаетесь оплатить полную стоимость заказа, включая стоимость доставки и применимые налоги, одним из способов оплаты, предлагаемых {{brandName}}. {{brandName}} вправе отменить неоплаченный заказ, если оплата не поступила в разумный срок.

            4. Изменение и отмена заказа
            После принятия заказа и начала его приготовления {{brandName}} может быть не в состоянии изменить или отменить его. Если вам необходимо изменить или отменить заказ, свяжитесь с {{brandName}} как можно скорее; отмена после начала приготовления может не подлежать возврату средств.

            5. Доставка и самовывоз
            {{brandName}} предпринимает разумные усилия для доставки или подготовки заказа в указанное ориентировочное время, однако возможны задержки по независящим от него причинам, включая дорожную обстановку, погодные условия и загруженность. Пожалуйста, проверяйте заказ при получении и незамедлительно сообщайте {{brandName}} о любых несоответствиях.

            6. Возврат, возмещение и претензии
            Если заказ доставлен не полностью, с ошибкой или не соответствует описанию, незамедлительно сообщите об этом {{brandName}} с указанием деталей, чтобы ситуацию можно было исправить — заменой, доукомплектованием или возвратом денежных средств, по усмотрению {{brandName}} и в соответствии с применимым законодательством. Приготовленные блюда, принятые в надлежащем состоянии, как правило, возврату не подлежат.

            7. Ваша учётная запись
            Вы несёте ответственность за достоверность и безопасность данных своей учётной записи и способа её подтверждения (например, номера телефона), а также за действия, совершённые через неё. Незамедлительно сообщите {{brandName}}, если полагаете, что ваша учётная запись использовалась без вашего разрешения.

            8. Ваши персональные данные
            {{brandName}} обрабатывает предоставленные вами персональные данные (имя, номер телефона, адрес доставки) для приёма и исполнения ваших заказов, связи с вами по их поводу, а также иных целей, указанных в его уведомлении о конфиденциальности. Вы можете обратиться к {{brandName}} с запросом о своих персональных данных или об их исправлении в порядке, предусмотренном применимым законодательством.

            9. Маркетинговые сообщения
            {{brandName}} может направлять вам сообщения, связанные с вашими заказами. Если он направляет вам маркетинговые или рекламные сообщения, вы можете отказаться от них в любое время без ущерба для возможности оформления заказов.

            10. Правила поведения
            Вы соглашаетесь не злоупотреблять сервисом — в том числе не оформлять заведомо ложные заказы, не злоупотреблять акциями и не вмешиваться в работу системы приёма заказов — и {{brandName}} вправе отказать в обслуживании или приостановить действие учётной записи, если обоснованно полагает, что это имело место.

            11. Ответственность
            В пределах, допускаемых применимым законодательством, ответственность {{brandName}} за проблему с заказом ограничивается суммой, уплаченной за этот заказ. Ничто в настоящих Условиях не ограничивает ответственность, которая не может быть ограничена по закону, и не исключает прав, предоставленных вам применимым законодательством о защите прав потребителей.

            12. Изменение настоящих Условий
            {{brandName}} может время от времени обновлять настоящие Условия — например, в связи с изменением своих услуг или законодательства. Новая версия вступает в силу с момента публикации и не изменяет то, на что вы согласились в отношении уже размещённого заказа. Продолжение использования сервиса после публикации новой версии означает её принятие; если это требуется по закону, {{brandName}} запросит ваше повторное согласие перед оформлением следующего заказа.

            13. Контакты
            Вопросы по настоящим Условиям или заказу можно направить {{brandName}} по контактным данным, указанным на этой витрине.

            Настоящие Условия являются типовым вариантом по умолчанию, предоставленным платформой. {{brandName}} может в любой момент заменить их собственными условиями.
            """;

    private static final String UZ_LATN_TEXT = """
            Foydalanish shartlari — {{brandName}}

            Ushbu Foydalanish shartlari («Shartlar») {{brandName}} onlayn-do'konidan \
            foydalanishingizni va u orqali berilgan buyurtmalaringizni tartibga soladi. Hisob \
            yaratish, buyurtma berish yoki xizmatdan boshqa tarzda foydalanish orqali siz \
            ushbu Shartlarni qabul qilasiz.

            1. Ushbu Shartlar kim o'rtasida tuziladi
            Ushbu do'kon orqali taklif etilayotgan mahsulot va xizmatlarni {{brandName}} \
            sotadi. Ushbu hujjat buyurtmalarni qabul qilish uchun ishlatiladigan dasturiy \
            platforma bilan hech qanday munosabat yaratmaydi; buyurtmangiz {{brandName}} \
            bilan tuziladi.

            2. Buyurtma berish
            Buyurtma {{brandName}} tomonidan tasdiqlangandan so'ng yetkazib berish yoki olib \
            ketish uchun qabul qilingan hisoblanadi. Narxlar, mavjud mahsulotlar, yetkazib \
            berish hududlari, yetkazib berish narxi, buyurtmaning minimal summasi va \
            taxminiy vaqt buyurtma berish paytida ko'rsatiladi va har bir buyurtmada farq \
            qilishi mumkin.

            3. To'lov
            Siz buyurtma uchun ko'rsatilgan to'liq narxni, jumladan yetkazib berish narxi va \
            tegishli soliqlarni, {{brandName}} taklif etadigan to'lov usullaridan biri orqali \
            to'lashga rozilik bildirasiz. Agar to'lov oqilona muddatda amalga oshirilmasa, \
            {{brandName}} to'lanmagan buyurtmani bekor qilishi mumkin.

            4. O'zgartirish va bekor qilish
            Buyurtma qabul qilingan va tayyorlash boshlangandan so'ng, {{brandName}} uni \
            o'zgartira yoki bekor qila olmasligi mumkin. Buyurtmani o'zgartirish yoki bekor \
            qilish zarur bo'lsa, imkon qadar tezroq {{brandName}} bilan bog'laning; \
            tayyorlash boshlangandan keyingi bekor qilish uchun mablag' qaytarilmasligi \
            mumkin.

            5. Yetkazib berish va olib ketish
            {{brandName}} buyurtmangizni ko'rsatilgan taxminiy vaqtda yetkazish yoki \
            tayyorlash uchun oqilona harakat qiladi, biroq tirbandlik, ob-havo va \
            buyurtmalar hajmi kabi o'zidan bog'liq bo'lmagan sabablarga ko'ra kechikishlar \
            yuz berishi mumkin. Buyurtmangizni qabul qilib olayotganda tekshirib chiqing va \
            har qanday muammo haqida {{brandName}}ga darhol xabar bering.

            6. Qaytarish, pul qaytarish va shikoyatlar
            Agar buyurtma to'liq bo'lmagan, noto'g'ri yoki tavsifga mos kelmagan holda yetib \
            kelsa, vaziyatni tuzatish uchun — almashtirish, to'ldirish yoki pulni \
            qaytarish orqali, {{brandName}}ning o'z ixtiyoriga ko'ra va amaldagi \
            qonunchilikka muvofiq — batafsil ma'lumot bilan darhol {{brandName}}ga \
            murojaat qiling. Yaxshi holatda qabul qilingan tayyor taomlar odatda \
            qaytarilmaydi.

            7. Sizning hisobingiz
            Hisobingiz va uni tasdiqlash usuli (masalan, telefon raqamingiz) to'g'ri va \
            xavfsiz bo'lishi, shuningdek hisobingiz orqali sodir bo'lgan har qanday faoliyat \
            uchun javobgarlik sizga yuklanadi. Agar hisobingizdan ruxsatisiz foydalanilgan \
            deb hisoblasangiz, darhol {{brandName}}ga xabar bering.

            8. Sizning shaxsiy ma'lumotlaringiz
            {{brandName}} siz taqdim etgan shaxsiy ma'lumotlarni (ism, telefon raqami, \
            yetkazib berish manzili) buyurtmalaringizni qabul qilish va bajarish, ular \
            yuzasidan siz bilan aloqa qilish hamda o'zining maxfiylik siyosatida ko'rsatilgan \
            boshqa maqsadlar uchun qayta ishlaydi. Amaldagi qonunchilikka muvofiq, \
            {{brandName}}dan sizning shaxsiy ma'lumotlaringiz haqida so'rov qilishingiz yoki \
            ularni to'g'irlashni so'rashingiz mumkin.

            9. Marketing xabarlari
            {{brandName}} sizga buyurtmalaringiz yuzasidan xabarlar yuborishi mumkin. Agar u \
            sizga marketing yoki reklama xabarlarini yuborsa, buyurtma berish \
            imkoniyatingizga ta'sir qilmagan holda istalgan vaqtda ulardan voz \
            kechishingiz mumkin.

            10. Xatti-harakat qoidalari
            Siz xizmatdan suiiste'mol qilmaslikka — jumladan, soxta buyurtmalar \
            bermaslik, aksiyalardan suiiste'mol qilmaslik yoki buyurtma tizimiga \
            aralashmaslikka — rozilik bildirasiz, va {{brandName}} bunday holat yuz \
            berganiga oqilona asosda ishonch hosil qilsa, xizmat ko'rsatishdan bosh tortishi \
            yoki hisobni to'xtatib turishi mumkin.

            11. Javobgarlik
            Amaldagi qonunchilik yo'l qo'ygan darajada, {{brandName}}ning buyurtmadagi \
            muammo uchun javobgarligi ushbu buyurtma uchun to'langan summa bilan \
            cheklanadi. Ushbu Shartlardagi hech narsa qonun bo'yicha cheklanishi mumkin \
            bo'lmagan javobgarlikni cheklamaydi va amaldagi iste'molchilarni himoya qilish \
            qonunchiligiga ko'ra sizga tegishli huquqlarni istisno qilmaydi.

            12. Ushbu Shartlarga o'zgartirishlar
            {{brandName}} vaqti-vaqti bilan ushbu Shartlarni, masalan, o'z xizmatlaridagi \
            yoki qonunchilikdagi o'zgarish sababli yangilashi mumkin. Yangi versiya e'lon \
            qilingan paytdan boshlab kuchga kiradi va allaqachon berilgan buyurtma bo'yicha \
            rozilik bildirgan narsangizni o'zgartirmaydi. Yangi versiya e'lon qilingandan \
            so'ng xizmatdan foydalanishni davom ettirish uni qabul qilganingizni bildiradi; \
            qonun talab qilgan hollarda, {{brandName}} keyingi buyurtmangizni berishdan \
            oldin qayta roziligingizni so'raydi.

            13. Aloqa
            Ushbu Shartlar yoki buyurtma yuzasidan savollarni ushbu do'konda ko'rsatilgan \
            aloqa ma'lumotlari orqali {{brandName}}ga yuborishingiz mumkin.

            Ushbu Shartlar platforma tomonidan taqdim etilgan umumiy standart hisoblanadi. \
            {{brandName}} ularni istalgan vaqtda o'zining shartlari bilan almashtirishi \
            mumkin.
            """;

    private static final Map<String, String> TEMPLATES = Map.of(
            TermsLocale.EN.tag(), EN_TEXT, TermsLocale.RU.tag(), RU_TEXT, TermsLocale.UZ_LATN.tag(), UZ_LATN_TEXT);
}
