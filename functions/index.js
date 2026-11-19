// Checks a Google Play subscription and records it at subs/{uid}, so the database rules can let
// a Sawaari Pro rider join a group as its 4th rider or later. Only this server code can write subs/.
//
// Setup (once): Firebase Blaze plan, then in Play Console → Users and permissions invite this
// project's service account (PROJECT_ID@appspot.gserviceaccount.com) with "View financial data"
// and "Manage orders and subscriptions". Deploy with: firebase deploy --only functions

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const { google } = require("googleapis");
const crypto = require("crypto");

admin.initializeApp();

const PACKAGE = "app.sawaari";
const PRODUCT = "sawaari_pro";
const ACTIVE = new Set(["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED"]);

exports.verifyPlaySubscription = onCall({ region: "asia-south1" }, async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const token = String((req.data && req.data.purchaseToken) || "");
  if (!token || token.length > 4096) throw new HttpsError("invalid-argument", "Missing purchase token.");

  const auth = new google.auth.GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });
  const publisher = google.androidpublisher({ version: "v3", auth });
  let sub;
  try {
    sub = (await publisher.purchases.subscriptionsv2.get({ packageName: PACKAGE, token })).data;
  } catch (e) {
    throw new HttpsError("not-found", "Google Play doesn't know this purchase.");
  }

  const items = (sub.lineItems || []).filter((i) => i.productId === PRODUCT);
  // A cancelled subscription stays usable until its paid period ends.
  const until = Math.max(0, ...items.map((i) => Date.parse(i.expiryTime || "") || 0));
  const plan = (items[0] && items[0].offerDetails && items[0].offerDetails.basePlanId) || "";
  if (!ACTIVE.has(sub.subscriptionState) || until <= Date.now()) {
    await admin.database().ref(`subs/${uid}`).remove();
    return { active: false, until };
  }

  // One purchase unlocks one rider at a time: moving it to a new phone (new sign-in) moves Pro too.
  const db = admin.database();
  const key = crypto.createHash("sha256").update(token).digest("hex");
  const owner = (await db.ref(`subTokens/${key}`).get()).val();
  const updates = { [`subTokens/${key}`]: uid, [`subs/${uid}`]: { until, plan, product: PRODUCT } };
  if (owner && owner !== uid) updates[`subs/${owner}`] = null;
  await db.ref().update(updates);
  return { active: true, until, plan };
});
