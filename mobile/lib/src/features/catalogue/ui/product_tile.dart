import 'package:flutter/material.dart';

import '../../../design/q_icon.dart';
import '../../../design/horecaos_theme.dart';
import '../../../design/horecaos_tokens.dart';
import '../data/menu.dart';
import 'availability_label.dart';
import 'catalogue_pressable.dart';

/// One product in a list.
///
/// A row and not a card with a photograph. `mediaAssetIds` arrives on every
/// product and there is no unauthenticated way to turn one into an image: the
/// media endpoints require `MEDIA_READ`, which is a staff capability. A tile
/// with a permanent grey placeholder where the photograph should be would make
/// every menu look broken, so the layout is one that does not want a picture.
class ProductTile extends StatelessWidget {
  const ProductTile({required this.product, super.key, this.onTap});

  final MenuProduct product;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final bool orderable = product.isOrderable;

    return CataloguePressable(
      onTap: onTap,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  product.name,
                  style: text.bodyLarge?.copyWith(
                    // A stopped item is stated as stopped and also drawn back,
                    // so it does not read as orderable at a glance. The word is
                    // what carries the meaning; this only stops the row from
                    // competing with the ones that can be ordered.
                    color: orderable ? tokens.ink : tokens.inkMuted,
                  ),
                ),
                if (product.description != null) ...<Widget>[
                  const SizedBox(height: HorecaOSGeometry.spaceXs),
                  Text(
                    product.description!,
                    style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
                const SizedBox(height: HorecaOSGeometry.spaceSm),
                if (orderable)
                  const PriceInBasketCaption()
                else
                  const SoldOutLabel(),
              ],
            ),
          ),
          if (onTap != null) ...<Widget>[
            const SizedBox(width: HorecaOSGeometry.spaceSm),
            QIcon(QIconName.chevronRight, color: tokens.inkSubtle),
          ],
        ],
      ),
    );
  }
}
