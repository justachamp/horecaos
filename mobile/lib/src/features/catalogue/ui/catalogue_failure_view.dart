import 'package:flutter/material.dart';

import '../../../design/q_empty_state.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../catalogue_controller.dart';

/// The four ways a menu can fail to appear, each said differently.
///
/// The correlation identifier is not on screen. It goes to telemetry, where a
/// support engineer can find it; putting it in front of a customer turns a bad
/// moment into a bad moment with a reference number they are expected to copy.
class CatalogueFailureView extends StatelessWidget {
  const CatalogueFailureView({required this.kind, super.key, this.onRetry});

  final MenuFailureKind kind;

  /// Absent where retrying cannot help. Nothing the customer does will make an
  /// unpublished menu publish, and offering "try again" there is a button that
  /// lies.
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    final (String title, String body, bool retryable) = switch (kind) {
      MenuFailureKind.notPublished => (
        l10n.catalogueNoMenuTitle,
        l10n.catalogueNoMenuBody,
        false,
      ),
      MenuFailureKind.forbidden => (
        l10n.catalogueForbiddenTitle,
        l10n.catalogueForbiddenBody,
        false,
      ),
      MenuFailureKind.offline => (
        l10n.catalogueOfflineTitle,
        l10n.catalogueOfflineBody,
        true,
      ),
      MenuFailureKind.unavailable => (
        l10n.catalogueUnavailableTitle,
        l10n.catalogueUnavailableBody,
        true,
      ),
    };

    return QEmptyState(
      title: title,
      body: body,
      actionLabel: retryable && onRetry != null ? l10n.retry : null,
      onAction: retryable ? onRetry : null,
    );
  }
}
