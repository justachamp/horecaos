import js from '@eslint/js';
import tseslint from 'typescript-eslint';

import horecaos from '../operations/tools/eslint-plugin-horecaos/index.js';
import rawTextParser from '../operations/tools/eslint-plugin-horecaos/raw-text-parser.js';

export default [
  {
    ignores: [
      'dist/**',
      '.angular/**',
      'node_modules/**',
      'coverage/**',
      // Generated, not authored: a copy of frontend/design-tokens/tokens.css, kept
      // honest by `npm run check:tokens`. It is where the closed type scale is
      // *defined*, so it is the one file allowed a raw px font-size.
      'src/design-system/tokens.css',
    ],
  },
  { ...js.configs.recommended, files: ['**/*.ts'] },
  ...tseslint.configs.recommended.map((config) => ({ ...config, files: ['**/*.ts'] })),
  {
    files: ['**/*.ts'],
    plugins: { horecaos },
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
      'horecaos/no-raw-px-font-size': 'error',
    },
  },
  {
    files: ['**/*.html', '**/*.css'],
    plugins: { horecaos },
    languageOptions: { parser: rawTextParser },
    rules: {
      'horecaos/no-raw-px-font-size': 'error',
    },
  },
];
