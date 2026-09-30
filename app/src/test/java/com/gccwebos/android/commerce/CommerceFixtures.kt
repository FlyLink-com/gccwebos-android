package com.gccwebos.android.commerce

/** Synthetic public API fixtures only; no account, payment, or production-order data. */
internal object CommerceFixtures {
    const val instant = "2026-09-30T00:00:00.000Z"
    const val expiresAt = "2026-09-30T00:10:00.000Z"
    val planOffer = """{
      "offerRef":"offer_fixture_plan","offerVersionRef":"offerv_fixture_plan_v1","category":"plan",
      "title":{"zh":"测试套餐","en":"Synthetic plan"},
      "summary":{"zh":"30 天，每月 50 GB","en":"30 days with 50 GB each month"},
      "importantNotices":{"zh":["仅用于离线测试"],"en":["Offline test only"]},
      "baseCoinUnits":10,"referencePrice":{"currency":"CNY","amountMinor":1000},
      "acceptedCoinTypes":["bonus_coins","paid_coins","reward_coins"],
      "delivery":{"kind":"subscription_plan","durationDays":30,"monthlyTrafficGb":50,
      "trafficGb":null,"neverExpires":false,"discountBasisPoints":0}
    }""".trimIndent()
    val offerBody = """{"offers":[$planOffer]}"""
    val legacyModel = """{"commerceModel":"legacy"}"""
    val walletFirstModel = """{"commerceModel":"wallet_first_unique_v1"}"""
    val wallet = """{"wallet":{"status":"active","version":1,
      "balances":{"paidCoins":20,"bonusCoins":0,"rewardCoins":0},
      "spendableUnits":20,"heldUnits":0,"pendingUnits":0}}""".trimIndent()
    val quote = """{"quote":{
      "quoteRef":"quote_fixture_plan","offer":$planOffer,"amountUnits":10,
      "appliedDiscountBasisPoints":0,"purchaseAvailability":"available","shortfallUnits":0,
      "allocation":[{"coinType":"paid_coins","amountUnits":10}],
      "balances":{"before":{"bonusCoins":0,"paidCoins":20,"rewardCoins":0,"heldCoins":0},
      "after":{"bonusCoins":0,"paidCoins":10,"rewardCoins":0,"heldCoins":10}},
      "expiresAt":"$expiresAt"}}""".trimIndent()
    val deferredQuote = quote.dropLast(2) + """, "transition":{"flowKind":"initial_activation","scheduledFor":"$instant"}}}"""
    val rechargeOffers = """{"offers":[{"offerRef":"recharge_fixture_10","quoteVersion":1,
      "paidCoinUnits":10,"amountMinor":1000,"currency":"CNY","currencyExponent":2,
      "paymentMethods":["alipay","wechat_pay"]}]}""".trimIndent()
    val rechargeOrder = """{"recharge":{"order":{
      "orderRef":"recharge_fixture_order","offerRef":"recharge_fixture_10","quoteVersion":1,
      "expectedPaidCoinUnits":10,"amountMinor":1000,"currency":"CNY","currencyExponent":2,
      "adapterKey":"xsd_v1","paymentMethod":"alipay","status":"entry_ready",
      "providerOrderRef":null,"nextAction":{"type":"redirect","url":"https://www.xsd8866.com/pay?fixture=synthetic","expiresAt":"$expiresAt"},
      "walletOperationRef":null,"walletLotRef":null,"expiresAt":"$expiresAt",
      "createdAt":"$instant","updatedAt":"$instant"}}}""".trimIndent()
    val order = """{"order":{
      "orderRef":"order_fixture_plan","offer":$planOffer,"amountUnits":10,
      "allocation":[{"coinType":"paid_coins","amountUnits":10}],"paymentStatus":"held",
      "cancelUntil":"$expiresAt","fulfillment":{"status":"not_requested","safeCode":null},
      "createdAt":"$instant","updatedAt":"$instant"}}""".trimIndent()
    val deferred = """{"transition":{
      "transitionRef":"transition_fixture_plan","offer":$planOffer,"amountUnits":10,
      "flowKind":"initial_activation","scheduledFor":"$instant","state":"reserved","safeCode":null,
      "reservedAt":"$instant","settledAt":null,"createdAt":"$instant","updatedAt":"$instant"}}""".trimIndent()
}
