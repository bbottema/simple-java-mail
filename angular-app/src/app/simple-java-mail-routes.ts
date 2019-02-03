import { Routes } from '@angular/router';

import {About} from './components/about/about';
import {Features} from './components/features/features';
import {Cli} from './components/cli/cli';
import {Configuration} from './components/configuration/configuration';
import {Debugging} from './components/debugging/debugging';
import {RfcCompliant} from './components/rfc/rfc';
import {Download} from './components/download/download';
import {Contact} from './components/contact/contact';
import {Dependencies} from "./components/dependencies/dependencies";

export const rootRouterConfig: Routes = [
  {path: 'about', component: About},
  {path: 'features', component: Features},
  {path: 'cli', component: Cli},
  {path: 'configuration', component: Configuration},
  {path: 'debugging', component: Debugging},
  {path: 'rfc', component: RfcCompliant},
  {path: 'download', component: Download},
  {path: 'contact', component: Contact},
  {path: 'dependencies', component: Dependencies},
  {path: '', redirectTo: 'about', pathMatch: 'full'}
];

