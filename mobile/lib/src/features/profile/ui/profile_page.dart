import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../app_scope.dart';
import '../../../auth/auth_session.dart';
import '../../../design/horecaos_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../data/customer_account.dart';
import '../profile_area.dart';
import '../profile_routes.dart';
import 'language_names.dart';
import 'profile_failure_view.dart';
import 'profile_scope.dart';
import 'profile_widgets.dart';

/// The root of the profile area: who the customer is, and everything else.
///
/// The account section and the settings section fail independently, and that is
/// deliberate. Resolving the account needs the platform; changing the interface
/// language does not. A screen that put both behind one loading state would
/// stop a customer changing their language because a capability they do not
/// hold refused a request they never made.
class ProfilePage extends StatefulWidget {
  const ProfilePage({super.key});

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  Future<CustomerAccount>? _account;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _account ??= ProfileScope.of(context).account();
  }

  void _retry() {
    // A block body, not an arrow: an arrow closure returns the assignment's
    // value, and `setState` refuses a callback that returns a Future.
    final ProfileArea area = ProfileScope.of(context);
    setState(() {
      _account = area.account();
    });
  }

  Future<void> _signOut() async {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final AuthSession session = AppScope.of(context).session;

    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: Text(l10n.profileSignOutConfirmTitle),
        content: Text(l10n.profileSignOutConfirmBody),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(l10n.profileCancel),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(l10n.signOut),
          ),
        ],
      ),
    );

    if (confirmed != true) {
      return;
    }
    // The area forgets the resolved account when the session goes; this forgets
    // the copy this screen is holding, so a second customer on the same phone
    // cannot see the first one's identifier for the frame before the router
    // moves.
    if (!mounted) {
      return;
    }
    setState(() => _account = null);
    await session.signOut();
    // No navigation. The guard reacts to the session's own notification, and
    // navigating from here as well would race it.
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final ProfileArea area = ProfileScope.of(context);
    final TextTheme text = Theme.of(context).textTheme;

    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.only(bottom: HorecaOSGeometry.spaceXl),
          children: <Widget>[
            Padding(
              padding: const EdgeInsets.fromLTRB(
                HorecaOSGeometry.spaceMd,
                HorecaOSGeometry.spaceLg,
                HorecaOSGeometry.spaceMd,
                0,
              ),
              child: Text(l10n.profileTitle, style: text.titleLarge),
            ),

            ProfileSectionHeader(l10n.profileAccountSection),
            _AccountSection(account: _account, onRetry: _retry),

            ProfileSectionHeader(l10n.profileSettingsSection),
            const ProfileDivider(),
            ProfileRow(
              title: l10n.profileAddresses,
              onTap: () => context.go(ProfileRoutes.addresses),
            ),
            const ProfileDivider(),
            ListenableBuilder(
              listenable: area.locale,
              builder: (BuildContext context, Widget? _) => ProfileRow(
                title: l10n.profileLanguage,
                value: area.locale.selected == null
                    ? l10n.profileLanguageSystem
                    : LanguageNames.of(area.locale.selected!),
                onTap: () => context.go(ProfileRoutes.language),
              ),
            ),
            const ProfileDivider(),
            ProfileRow(
              title: l10n.profileNotifications,
              onTap: () => context.go(ProfileRoutes.notifications),
            ),
            const ProfileDivider(),

            const SizedBox(height: HorecaOSGeometry.spaceLg),
            ProfileRow(
              title: l10n.signOut,
              destructive: true,
              onTap: _signOut,
            ),
            const ProfileDivider(),
          ],
        ),
      ),
    );
  }
}

/// Who the customer is, as far as this application can honestly say.
///
/// The account identifier and nothing else. `customer.customer_accounts` also
/// holds a display name, but no endpoint returns it and the identity token's
/// claims are not validated in this application — ADR 0035 is explicit that
/// nothing here makes a decision from them. Rendering a name from an unverified
/// claim would put a string on screen that the platform never agreed to.
class _AccountSection extends StatelessWidget {
  const _AccountSection({required this.account, required this.onRetry});

  final Future<CustomerAccount>? account;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return FutureBuilder<CustomerAccount>(
      future: account,
      builder:
          (BuildContext context, AsyncSnapshot<CustomerAccount> snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Padding(
                padding: EdgeInsets.all(HorecaOSGeometry.spaceMd),
                child: Center(child: CircularProgressIndicator()),
              );
            }
            final Object? failure = snapshot.error;
            if (failure != null) {
              return Padding(
                padding: const EdgeInsets.symmetric(
                  vertical: HorecaOSGeometry.spaceMd,
                ),
                child: ProfileFailureView(failure: failure, onRetry: onRetry),
              );
            }
            final CustomerAccount? resolved = snapshot.data;
            if (resolved == null) {
              return const SizedBox.shrink();
            }
            return Column(
              children: <Widget>[
                const ProfileDivider(),
                ProfileRow(
                  title: l10n.profileAccountReference,
                  detail: resolved.accountId,
                ),
                const ProfileDivider(),
                Padding(
                  padding: const EdgeInsets.fromLTRB(
                    HorecaOSGeometry.spaceMd,
                    HorecaOSGeometry.spaceSm,
                    HorecaOSGeometry.spaceMd,
                    0,
                  ),
                  child: Text(
                    l10n.profileAccountReferenceHint,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
              ],
            );
          },
    );
  }
}
