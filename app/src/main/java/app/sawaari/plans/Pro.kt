package app.sawaari.plans

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** A plan the rider can buy, with the store's own price text when it has loaded. */
data class ProOffer(val basePlan: String, val title: String, val price: String, val note: String)

data class ProState(
    val active: Boolean = false,
    /** Store connected and the subscription found, so the Buy buttons work. */
    val storeReady: Boolean = false,
    val offers: List<ProOffer> = defaultOffers,
    val message: String? = null,
) {
    companion object {
        val defaultOffers = listOf(
            ProOffer(Plans.PLAN_QUARTERLY, "3 months", Plans.PRICE_QUARTERLY, "Renews every 3 months"),
            ProOffer(Plans.PLAN_YEARLY, "1 year", Plans.PRICE_YEARLY, "Best value: save ₹300 a year"),
        )
    }
}

/**
 * Sawaari Pro through Google Play Billing: one subscription ([Plans.PRODUCT_ID]) with a 3-month and a
 * yearly base plan. The phone trusts Play for the garage; bigger ride groups also need the server to
 * record the subscription (functions/verifyPlaySubscription), because the database rules check that.
 */
object Pro {
    private const val PREFS = "plans"
    private const val KEY_TRIAL_START = "garage_trial_start"
    private const val KEY_ACTIVE = "pro_active"

    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope
    private var client: BillingClient? = null
    private var details: ProductDetails? = null

    private val _state = MutableStateFlow(ProState())
    val state: StateFlow<ProState> = _state.asStateFlow()

    fun init(context: Context, appScope: CoroutineScope) {
        appContext = context.applicationContext
        scope = appScope
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The garage trial starts the first time this version runs, so riders who already had the app get 2 weeks too.
        if (!prefs.contains(KEY_TRIAL_START)) prefs.edit().putLong(KEY_TRIAL_START, System.currentTimeMillis()).apply()
        // Last known answer from Play, so the garage opens offline for subscribers.
        _state.value = ProState(active = prefs.getBoolean(KEY_ACTIVE, false))
        connect()
    }

    fun garageTrialDaysLeft(now: Long = System.currentTimeMillis()): Int =
        Plans.garageTrialDaysLeft(trialStart(), now)

    fun garageOpen(now: Long = System.currentTimeMillis()): Boolean =
        Plans.garageOpen(trialStart(), now, _state.value.active)

    private fun trialStart(): Long =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TRIAL_START, System.currentTimeMillis())

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    private fun connect() {
        val c = BillingClient.newBuilder(appContext)
            .setListener { result, purchases -> onPurchases(result, purchases) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) scope.launch { loadProduct(); refresh() }
            }
            override fun onBillingServiceDisconnected() { client = null }
        })
    }

    private suspend fun loadProduct() {
        val c = client ?: return
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(Plans.PRODUCT_ID).setProductType(BillingClient.ProductType.SUBS).build()
            )
        ).build()
        val d = c.queryProductDetails(params).productDetailsList?.firstOrNull() ?: return
        details = d
        val offers = ProState.defaultOffers.map { o ->
            val price = d.subscriptionOfferDetails?.firstOrNull { it.basePlanId == o.basePlan }
                ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            if (price != null) o.copy(price = price) else o
        }
        _state.value = _state.value.copy(storeReady = true, offers = offers)
    }

    /** Ask Play which subscriptions this Google account has (e.g. after reinstalling or on a new phone). */
    fun refresh() = scope.launch {
        val c = client ?: run { connect(); return@launch }
        val result = c.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return@launch
        handle(result.purchasesList)
    }

    /** Open Google Play's checkout for [basePlan] (quarterly or yearly). */
    fun buy(activity: Activity, basePlan: String) {
        val c = client
        val d = details
        val offer = d?.subscriptionOfferDetails?.firstOrNull { it.basePlanId == basePlan }
        if (c == null || d == null || offer == null) {
            _state.value = _state.value.copy(
                message = "Subscriptions work when Sawaari is installed from Google Play. Check your internet and try again."
            )
            if (c == null) connect()
            return
        }
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).setOfferToken(offer.offerToken).build())
        ).build()
        c.launchBillingFlow(activity, params)
    }

    private fun onPurchases(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { handle(purchases.orEmpty(), justBought = true) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> _state.value = _state.value.copy(message = "The purchase didn't go through (${result.debugMessage.ifBlank { "error ${result.responseCode}" }}).")
        }
    }

    private suspend fun handle(purchases: List<Purchase>, justBought: Boolean = false) {
        val mine = purchases.filter { Plans.PRODUCT_ID in it.products }
        val paid = mine.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        paid.filterNot { it.isAcknowledged }.forEach { p ->
            client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
        }
        setActive(paid.isNotEmpty())
        paid.firstOrNull()?.let { registerWithServer(it.purchaseToken) }
        when {
            justBought && paid.isNotEmpty() -> _state.value = _state.value.copy(message = "Welcome to Sawaari Pro. Thank you for supporting Sawaari!")
            justBought && mine.any { it.purchaseState == Purchase.PurchaseState.PENDING } ->
                _state.value = _state.value.copy(message = "Payment is pending. Pro switches on as soon as Google Play confirms it.")
        }
    }

    private fun setActive(active: Boolean) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, active).apply()
        _state.value = _state.value.copy(active = active)
    }

    /** Lets the server check the purchase with Google and mark this rider as Pro for group seats. */
    private suspend fun registerWithServer(token: String) {
        if (FirebaseApp.getApps(appContext).isEmpty()) return
        runCatching {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) auth.signInAnonymously().await()
            FirebaseFunctions.getInstance("asia-south1").getHttpsCallable("verifyPlaySubscription")
                .call(mapOf("purchaseToken" to token)).await()
        }
    }
}
