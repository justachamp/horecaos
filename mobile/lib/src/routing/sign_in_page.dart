import 'package:flutter/material.dart';

import '../auth/auth_session.dart';
import '../auth/tokens.dart';
import '../design/qoida_theme.dart';
import '../design/qoida_tokens.dart';
import '../l10n/generated/app_localizations.dart';

/// The sign-in route.
///
/// This is plumbing, not a product screen. It exists because the guard needs
/// somewhere to send a signed-out customer and because the PKCE flow needs
/// something to start it. Onboarding — the carousel the archived application
/// opened with — is a designed screen and belongs to whoever builds it against
/// `docs/operations-spec/` and the prototypes.
class SignInPage extends StatefulWidget {
  const SignInPage({required this.session, super.key});

  final AuthSession session;

  @override
  State<SignInPage> createState() => _SignInPageState();
}

class _SignInPageState extends State<SignInPage> {
  bool _busy = false;
  bool _failed = false;

  Future<void> _signIn() async {
    setState(() {
      _busy = true;
      _failed = false;
    });
    try {
      await widget.session.signIn(
        uiLocale: Localizations.localeOf(context).toLanguageTag(),
      );
      // No navigation here. The guard reacts to the session's notification and
      // moves; navigating from both places would race and could push a screen
      // the guard then replaces.
    } on AuthException catch (failure) {
      if (!mounted) return;
      setState(() {
        // A customer who dismissed the browser knows they dismissed it.
        // Reporting it back as an error is the application arguing with them.
        _failed = failure.code != 'user_cancelled';
      });
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final TextTheme text = Theme.of(context).textTheme;

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(QoidaGeometry.spaceLg),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(l10n.signInTitle, style: text.titleLarge),
              const SizedBox(height: QoidaGeometry.spaceSm),
              Text(l10n.signInBody, style: text.bodyMedium),
              if (_failed) ...<Widget>[
                const SizedBox(height: QoidaGeometry.spaceMd),
                Text(
                  l10n.signInFailed,
                  style: text.bodyMedium?.copyWith(
                    color: context.qoida.errorInk,
                  ),
                ),
              ],
              const SizedBox(height: QoidaGeometry.spaceLg),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: _busy ? null : _signIn,
                  child: Text(l10n.signInAction),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
