/*
 * Activator.java
 *
 * Tigase Jabber/XMPP Server - SOCKS5 Proxy
 * Copyright (C) 2004-2017 "Tigase, Inc." <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */

package tigase.socks5;

import org.osgi.framework.*;
import tigase.osgi.ModulesManager;

import java.util.logging.Logger;

public class Activator
		implements BundleActivator, ServiceListener {

	private static final Logger log = Logger.getLogger(Activator.class.getCanonicalName());
	private BundleContext context = null;
	private ModulesManager serviceManager = null;
	private ServiceReference serviceReference = null;
	private Class<Socks5ProxyComponent> socks5ComponentClass = null;

	@Override
	public void start(BundleContext bc) throws Exception {
		synchronized (this) {
			context = bc;
			socks5ComponentClass = Socks5ProxyComponent.class;
			bc.addServiceListener(this, "(&(objectClass=" + ModulesManager.class.getName() + "))");
			serviceReference = bc.getServiceReference(ModulesManager.class.getName());
			if (serviceReference != null) {
				serviceManager = (ModulesManager) bc.getService(serviceReference);
				registerAddons();
			}
		}
	}

	@Override
	public void stop(BundleContext bc) throws Exception {
		synchronized (this) {
			if (serviceManager != null) {
				unregisterAddons();
				context.ungetService(serviceReference);
				serviceManager = null;
				serviceReference = null;
			}
			//mucComponent.stop();
			socks5ComponentClass = null;
		}
	}

	@Override
	public void serviceChanged(ServiceEvent event) {
		if (event.getType() == ServiceEvent.REGISTERED) {
			if (serviceReference == null) {
				serviceReference = event.getServiceReference();
				serviceManager = (ModulesManager) context.getService(serviceReference);
				registerAddons();
			}
		} else if (event.getType() == ServiceEvent.UNREGISTERING) {
			if (serviceReference == event.getServiceReference()) {
				unregisterAddons();
				context.ungetService(serviceReference);
				serviceManager = null;
				serviceReference = null;
			}
		}
	}

	private void registerAddons() {
		if (serviceManager != null) {
			serviceManager.registerServerComponentClass(socks5ComponentClass);
			serviceManager.update();
		}
	}

	private void unregisterAddons() {
		if (serviceManager != null) {
			serviceManager.unregisterServerComponentClass(socks5ComponentClass);
			serviceManager.update();
		}
	}
}
