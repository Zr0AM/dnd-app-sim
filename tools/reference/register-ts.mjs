// Lets Node load the upstream TypeScript sim directly: its sources use extensionless relative imports
// (bundler-style resolution), so fall back to "<specifier>.ts" when the plain specifier does not resolve.
//   node --experimental-strip-types --import ./tools/reference/register-ts.mjs <script>.mts
import { register } from 'node:module';

register('./ts-resolve-hooks.mjs', import.meta.url);
