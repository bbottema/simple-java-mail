import { Routes } from '@angular/router';

import {About} from './components/about/about';
import {Features} from './components/features/features';
import {Configuration} from './components/configuration/configuration';
import {Debugging} from './components/debugging/debugging';
import {RfcCompliant} from './components/rfc/rfc';
import {Download} from './components/download/download';
import {Contact} from './components/contact/contact';
import {Migrate500} from "./components/migrate-5.0.0/migrate-5.0.0";

export const rootRouterConfig: Routes = [
  {path: 'about', component: About},
  {path: 'features', component: Features},
  {path: 'configuration', component: Configuration},
  {path: 'debugging', component: Debugging},
  {path: 'rfc', component: RfcCompliant},
  {path: 'download', component: Download},
  {path: 'contact', component: Contact},
  {path: 'migrate500', component: Migrate500},
  {path: '', redirectTo: 'about', pathMatch: 'full'}
];

