const test = require("node:test");
const assert = require("node:assert/strict");
const { subscriptionDocFrom, uidFrom, HANDLED_EVENTS } = require("../subscription");

test("maps an active subscription with the period end on the item (current Stripe API)", () => {
  const doc = subscriptionDocFrom(
    {
      id: "sub_1",
      status: "active",
      cancel_at_period_end: false,
      items: { data: [{ current_period_end: 1_800_000_000, price: { id: "price_x" } }] },
    },
    123
  );
  assert.deepEqual(doc, {
    status: "active",
    currentPeriodEnd: 1_800_000_000_000,
    priceId: "price_x",
    stripeSubscriptionId: "sub_1",
    cancelAtPeriodEnd: false,
    updatedAt: 123,
  });
});

test("falls back to the subscription-level period end (older API payloads)", () => {
  const doc = subscriptionDocFrom({ status: "trialing", current_period_end: 1_700_000_000, items: { data: [] } }, 1);
  assert.equal(doc.currentPeriodEnd, 1_700_000_000_000);
  assert.equal(doc.priceId, null);
  assert.equal(doc.status, "trialing");
});

test("a deleted subscription keeps its canceled status so the gate shuts", () => {
  const doc = subscriptionDocFrom({ id: "sub_2", status: "canceled", current_period_end: 0 }, 5);
  assert.equal(doc.status, "canceled");
  assert.equal(doc.currentPeriodEnd, 0);
});

test("uid comes from metadata first, then client_reference_id", () => {
  assert.equal(uidFrom({ metadata: { firebaseUid: "u1" }, client_reference_id: "u2" }), "u1");
  assert.equal(uidFrom({ metadata: {}, client_reference_id: "u2" }), "u2");
  assert.equal(uidFrom({}), null);
  assert.equal(uidFrom(null), null);
});

test("only subscription-affecting events are handled", () => {
  assert.ok(HANDLED_EVENTS.has("customer.subscription.updated"));
  assert.ok(HANDLED_EVENTS.has("checkout.session.completed"));
  assert.ok(!HANDLED_EVENTS.has("invoice.paid"));
});
