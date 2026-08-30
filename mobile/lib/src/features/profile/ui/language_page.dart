import 'dart:async';

import 'package:flutter/material.dart';

import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../settings/locale_preference.dart';
import 'language_names.dart';
import 'profile_scope.dart';
import 'profile_widgets.dart';

/// Choosing the interface language.
///
/// Four options, not three. "Device language" is a real choice and not the
/// absence of one: it means "keep following the phone", and a customer who
/// picks Russian on a Russian phone should still read Russian after the phone
/// is switched to Uzbek. Storing the resolved locale instead of the absence of a
/// choice would collapse the two.
///
/// Each language is written in itself. Nobody looking for Uzbek looks for
/// "узбекский"; see `LanguageNames` for why these three strings are the only
/// user-visible text in this feature that is not an ARB message.
class LanguagePage extends StatelessWidget {
  const LanguagePage({super.key});

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final LocalePreference preference = ProfileScope.of(context).locale;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.profileLanguage)),
      body: SafeArea(
        child: ListenableBuilder(
          listenable: preference,
          builder: (BuildContext context, Widget? _) {
            return RadioGroup<String>(
              // Keyed by language tag rather than by `Locale`, with the empty
              // string standing for "follow the device". `Locale` has value
              // equality, but a nullable group value would make "no selection"
              // and "follow the device" the same state to this widget, and they
              // are not.
              groupValue: preference.selected?.toLanguageTag() ?? '',
              onChanged: (String? tag) {
                if (tag == null) {
                  return;
                }
                // Not awaited: the choice is applied in memory and the screen
                // has already rebuilt, so awaiting would only put the next tap
                // behind a keystore write. `select` handles a failed write
                // itself rather than throwing at a settings screen.
                unawaited(preference.select(_localeFor(tag)));
              },
              child: ListView(
                padding: const EdgeInsets.only(bottom: QoidaGeometry.spaceXl),
                children: <Widget>[
                  const ProfileDivider(),
                  ProfileRow(
                    title: l10n.profileLanguageSystem,
                    detail: l10n.profileLanguageSystemHelp,
                    trailing: const Radio<String>(value: ''),
                    onTap: () => unawaited(preference.select(null)),
                  ),
                  const ProfileDivider(),
                  for (final Locale locale in LanguageNames.offered) ...<Widget>[
                    ProfileRow(
                      title: LanguageNames.of(locale),
                      trailing: Radio<String>(value: locale.toLanguageTag()),
                      onTap: () => unawaited(preference.select(locale)),
                    ),
                    const ProfileDivider(),
                  ],
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  static Locale? _localeFor(String tag) {
    if (tag.isEmpty) {
      return null;
    }
    for (final Locale locale in LanguageNames.offered) {
      if (locale.toLanguageTag() == tag) {
        return locale;
      }
    }
    return null;
  }
}
