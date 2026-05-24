import { execSync } from 'node:child_process';
import { existsSync, cpSync, mkdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const backendSpec = resolve(__dirname, '../../api/restaurant-api.yml');

if (!existsSync(backendSpec)) {
    throw new Error(`OpenAPI contract not found at ${backendSpec}`);
}

mkdirSync('./openapi', { recursive: true });
cpSync(backendSpec, './openapi/api.yaml');

execSync(
  [
    'npx @openapitools/openapi-generator-cli generate',
    '-i ./openapi/api.yaml',
    '-g typescript-axios',
    '-o ./src/api/generated',
    '--additional-properties=supportsES6=true,useSingleRequestParameter=true,withSeparateModelsAndApi=true,apiPackage=api,modelPackage=model'
  ].join(' '),
  { stdio: 'inherit' }
);
