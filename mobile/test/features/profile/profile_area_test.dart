import 'package:flutter_test/flutter_test.dart';
import 'package:qoida_mobile/src/api/api_exception.dart';
import 'package:qoida_mobile/src/api/problem_details.dart';
import 'package:qoida_mobile/src/auth/auth_session.dart';
import 'package:qoida_mobile/src/features/profile/data/customer_account.dart';
import 'package:qoida_mobile/src/features/profile/profile_area.dart';

import 'profile_harness.dart';

void main() {
  test('the account is resolved once, however many screens ask', () async {
    // Resolving is a mutation — it creates the account on a first sign-in — so
    // resolving per screen would send that mutation on every navigation.
    final FakeAccounts accounts = FakeAccounts();
    final ProfileArea area = fakeArea(accounts: accounts);

    final List<CustomerAccount> resolved = await Future.wait(
      <Future<CustomerAccount>>[area.account(), area.account()],
    );
    await area.account();

    expect(accounts.calls, 1);
    expect(resolved.first.accountId, testAccount.accountId);
  });

  test('concurrent callers share one idempotency key', () async {
    final FakeAccounts accounts = FakeAccounts();
    final ProfileArea area = fakeArea(accounts: accounts);

    await Future.wait(<Future<CustomerAccount>>[
      area.account(),
      area.account(),
    ]);

    expect(accounts.keys, hasLength(1));
  });

  test('a failed resolution is not remembered, so a retry retries', () async {
    // Memoising the failure would leave a customer looking at the same error
    // until they killed the application.
    final FakeAccounts accounts = FakeAccounts(
      failure: const ApiTransportException('timeout'),
    );
    final ProfileArea area = fakeArea(accounts: accounts);

    await expectLater(area.account(), throwsA(isA<ApiTransportException>()));
    accounts.failure = null;
    final CustomerAccount second = await area.account();

    expect(accounts.calls, 2);
    expect(second.accountId, testAccount.accountId);
  });

  test('signing out forgets the account identifier', () async {
    // On a shared phone, a remembered identifier would send one customer's
    // addresses to the next customer's screen.
    final FakeAccounts accounts = FakeAccounts();
    final AuthSession session = testSession();
    final ProfileArea area = fakeArea(accounts: accounts, session: session);
    addTearDown(area.dispose);

    await session.restore();
    await area.account();
    expect(accounts.calls, 1);

    await session.signOut();
    await area.account();

    expect(accounts.calls, 2);
  });

  test('a refusal reaches the caller rather than being swallowed', () async {
    // The endpoints this feature calls declare staff capabilities that no
    // customer principal holds. The screen has to be able to say so.
    final ProfileArea area = fakeArea(
      accounts: FakeAccounts(
        failure: const ApiException(
          ProblemDetails(
            status: 403,
            code: ApiErrorCode.insufficientCapability,
          ),
        ),
      ),
    );

    await expectLater(
      area.account(),
      throwsA(
        isA<ApiException>().having(
          (ApiException failure) => failure.isForbidden,
          'isForbidden',
          isTrue,
        ),
      ),
    );
  });
}
