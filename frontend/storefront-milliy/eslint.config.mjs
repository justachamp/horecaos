import js from '@eslint/js';
import tseslint from 'typescript-eslint';

import horecaos from '../operations/tools/eslint-plugin-horecaos/index.js';
import rawTextParser from '../operations/tools/eslint-plugin-horecaos/raw-text-parser.js';

export default [
  {
    ignores: ['dist/**', '.angular/**', 'node_modules/**', 'coverage/**'],
  },
  { ...js.configs.recommended, files: ['**/*.ts'] },
  ...tseslint.configs.recommended.map((config) => ({ ...config, files: ['**/*.ts'] })),
  {
    files: ['**/*.ts'],
    rules: {
      eqeqeq: ['error', 'smart'],
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          // A leading underscore is the language-wide "deliberately unused": a
          // callback that must accept an argument it ignores, or a destructured
          // sibling dropped from a rest.
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          destructuredArrayIgnorePattern: '^_',
          ignoreRestSiblings: true,
        },
      ],
    },
  },
  {
    // ADR 0149, Decision 6: the writing direction is the registry's to state, so a stylesheet says
    // `margin-inline-start`, not `margin-left`. The storefronts' layout is mostly Tailwind
    // utilities (already logical where it matters); this covers the hand-written component SCSS.
    // A ratchet: the stylesheets that predate it are listed in the plugin's per-app baseline,
    // which can only shrink.
    files: ['**/*.css', '**/*.scss'],
    plugins: { horecaos },
    languageOptions: { parser: rawTextParser },
    rules: {
      'horecaos/no-physical-direction': 'error',
    },
  },
];
