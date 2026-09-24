package com.lutfi.softpostester

object Config {
    // Arab Bank's branded M4Bank SoftPOS app
    const val SOFTPOS_PACKAGE = "com.arabbank.softpos"

    // Serialization keys from the PayBox interaction protocol
    const val KEY_INPUT_DATA = "ru.m4bank.ExternalApplication.InputDataKey"
    const val KEY_RESULT_DATA = "ru.m4bank.ExternalApplication.ResultDataKey"
    const val KEY_OPERATION_TYPE = "ru.m4bank.ExternalApplication.OperationTypeKey"

    // Operation types
    const val OP_SIGN_IN = "SIGN_IN_APPLICATION"
    const val OP_PAYMENT = "PAYMENT"
    const val OP_REVERSAL = "REVERSAL"
    const val OP_LIST = "OPERATIONS_LIST"

    // ILS, ISO 4217 numeric
    const val CURRENCY_ILS = 376
    const val AMOUNT_EXPONENT = 2

    // Token service on Render
    const val DEFAULT_SERVER_URL = "https://ab-softpos-api.onrender.com"
    const val DEFAULT_READ_KEY = "HcNGhhe1Sp8VpC4u7PCTWVFS9jdYf3QuIlv2rOvkVDM"

    // Values from the protocol's own example. Confirm the Palestine
    // values with the bank and change them in the app's Advanced fields.
    const val DEFAULT_TAX_RATE = "TAX_20"
    const val DEFAULT_ACCOUNTING_SUBJECT = "PRODUCT"
}
