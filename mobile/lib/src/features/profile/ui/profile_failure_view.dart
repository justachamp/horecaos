import 'package:flutter/material.dart';

import '../../../api/api_exception.dart';
import '../../../api/problem_details.dart';
import '../../../design/q_empty_state.dart';
import '../../../l10n/generated/app_localizations.dart';

/// What a failed request looks like to a customer.
///
/// Three outcomes, and the third one is the point.
///
/// - **A transport failure** is the customer's own network. Retrying is the
///   right action and the button offers it.
/// - **Anything else the server said** is ours. One apology, one retry, and no
///   technical detail: the correlation identifier goes to telemetry, which is
///   where a support conversation picks it up.
/// - **A refusal** — 403, or `INSUFFICIENT_CAPABILITY` — is neither, and it is
///   the state this application is actually in today. The storefront and
///   customer endpoints declare staff capabilities that no customer principal
///   holds; ADR 0025 has not yet settled what a non-staff principal is, and
///   until it does these screens answer 403 to a real customer. That is a
///   recorded open item, so it renders as a plain statement with **no retry
///   button**: a capability denial does not become a grant by being asked
///   again, and a retry loop against one is an application that appears to be
///   trying while achieving nothing.
class ProfileFailureView extends StatelessWidget {
  const ProfileFailureView({required this.failure, super.key, this.onRetry});

  /// An `ApiFailure`, or anything else that came back from a repository.
  final Object failure;

  final VoidCallback? onRetry;

  /// Whether this failure is worth offering a retry for.
  static bool isRetryable(Object failure) => !_isRefusal(failure);

  static bool _isRefusal(Object failure) {
    if (failure is! ApiException) {
      return false;
    }
    return failure.isForbidden ||
        failure.code == ApiErrorCode.insufficientCapability ||
        failure.code == ApiErrorCode.tenantAccessDenied;
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    final String title;
    final String body;
    if (_isRefusal(failure)) {
      title = l10n.profileUnavailableTitle;
      body = l10n.profileUnavailableBody;
    } else if (failure is ApiTransportException) {
      title = l10n.profileOfflineTitle;
      body = l10n.profileOfflineBody;
    } else {
      title = l10n.profileErrorTitle;
      body = l10n.profileErrorBody;
    }

    final bool offerRetry = onRetry != null && isRetryable(failure);
    return QEmptyState(
      title: title,
      body: body,
      actionLabel: offerRetry ? l10n.retry : null,
      onAction: offerRetry ? onRetry : null,
    );
  }
}
