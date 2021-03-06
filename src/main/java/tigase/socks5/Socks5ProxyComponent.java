/*
 * Tigase Socks5 Component - SOCKS5 proxy component for Tigase
 * Copyright (C) 2011 Tigase, Inc. (office@tigase.com)
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

//~--- non-JDK imports --------------------------------------------------------

import tigase.cluster.api.ClusterCommandException;
import tigase.cluster.api.ClusterControllerIfc;
import tigase.cluster.api.ClusteredComponentIfc;
import tigase.cluster.api.CommandListenerAbstract;
import tigase.db.TigaseDBException;
import tigase.kernel.beans.Bean;
import tigase.kernel.beans.Initializable;
import tigase.kernel.beans.Inject;
import tigase.kernel.beans.config.ConfigField;
import tigase.kernel.beans.selector.ConfigType;
import tigase.kernel.beans.selector.ConfigTypeEnum;
import tigase.kernel.core.Kernel;
import tigase.server.Iq;
import tigase.server.Message;
import tigase.server.Packet;
import tigase.server.Priority;
import tigase.socks5.repository.Socks5Repository;
import tigase.util.Algorithms;
import tigase.util.dns.DNSEntry;
import tigase.util.dns.DNSResolverFactory;
import tigase.util.stringprep.TigaseStringprepException;
import tigase.xml.Element;
import tigase.xmpp.Authorization;
import tigase.xmpp.PacketErrorTypeException;
import tigase.xmpp.StanzaType;
import tigase.xmpp.jid.JID;

import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class description
 *
 * @author <a href="mailto:andrzej.wojcik@tigase.org">Andrzej W??jcik</a>
 * @version Enter version here..., 13/02/16
 */
@Bean(name = "socks5", parent = Kernel.class, active = false)
@ConfigType(ConfigTypeEnum.DefaultMode)
public class Socks5ProxyComponent
		extends Socks5ConnectionManager
		implements ClusteredComponentIfc, Initializable {

	private static final String[] IQ_QUERY_ACTIVATE_PATH = {"iq", "query", "activate"};
	private static final Logger log = Logger.getLogger(Socks5ProxyComponent.class.getCanonicalName());
	private static final String PACKET_FORWARD_CMD = "socks5-packet-forward";
	private static final String[] QUERY_ACTIVATE_PATH = {"query", "activate"};
	private static final String XMLNS_BYTESTREAMS = "http://jabber.org/protocol/bytestreams";

	//~--- fields ---------------------------------------------------------------
	private final List<JID> cluster_nodes = new LinkedList<JID>();
	private ClusterControllerIfc clusterController = null;
	private PacketForward packetForwardCmd = new PacketForward();
	@ConfigField(desc = "Remote IP addresses", alias = "remote-addresses")
	private String[] remoteAddresses = null;
	@Inject
	private Socks5Repository socks5_repo = null;
	@Inject
	private VerifierIfc verifier = null;

	public Socks5ProxyComponent() {
	}

	@Override
	public synchronized void everyHour() {
		super.everyHour();
	}

	/**
	 * Handle connection of other node of cluster
	 *
	 * @param node
	 */
	@Override
	public void nodeConnected(String node) {
		try {
			cluster_nodes.add(JID.jidInstance(getName() + "@" + node));
		} catch (TigaseStringprepException e) {
			log.log(Level.WARNING, "TigaseStringprepException occured processing {0}", node);
		}
	}

	/**
	 * Handle disconnection of other node of cluster
	 *
	 * @param node
	 */
	@Override
	public void nodeDisconnected(String node) {
		try {
			cluster_nodes.remove(JID.jidInstance(getName() + "@" + node));
		} catch (TigaseStringprepException e) {
			log.log(Level.WARNING, "TigaseStringprepException occured processing {0}", node);
		}
	}

	@Override
	public void processPacket(Packet packet) {
		try {

			// forwarding response from other node to client
			if ((packet.getPacketFrom() != null) && packet.getPacketFrom().getLocalpart().equals(getName()) &&
					cluster_nodes.contains(packet.getPacketFrom())) {
				packet.setPacketFrom(getComponentId());
				packet.setPacketTo(null);
				addOutPacket(packet);

				return;
			}
			if (packet.getType() == StanzaType.error) {

				// dropping packet of type error
				return;
			}
			if (packet.getElement().getChild("query", XMLNS_BYTESTREAMS) != null) {
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "processing bytestream query packet = {0}", packet);
				}

				Element query = packet.getElement().getChild("query");

				if (query.getChild("activate") == null) {
					try {
						String jid = packet.getStanzaTo().getBareJID().toString();
						String hostname = getComponentId().getDomain();

						// Generate list of streamhosts
						List<Element> children = new LinkedList<Element>();

						if ((remoteAddresses == null) || (remoteAddresses.length == 0)) {
							DNSEntry[] entries = DNSResolverFactory.getInstance().getHostSRV_Entries(hostname);

							for (DNSEntry entry : entries) {
								int[] ports = getPorts();

								for (int port : ports) {
									Element streamhost = new Element("streamhost");

									streamhost.setAttribute("jid", jid);
									streamhost.setAttribute("host", entry.getIp());
									streamhost.setAttribute("port", String.valueOf(port));
									children.add(streamhost);
								}
							}
						} else {
							for (String addr : remoteAddresses) {
								int[] ports = getPorts();

								for (int port : ports) {
									Element streamhost = new Element("streamhost");

									streamhost.setAttribute("jid", jid);
									streamhost.setAttribute("host", addr);
									streamhost.setAttribute("port", String.valueOf(port));
									children.add(streamhost);
								}
							}
						}

						// Collections.reverse(children);
						query.addChildren(children);
						addOutPacket(packet.okResult(query, 0));
					} catch (UnknownHostException e) {
						addOutPacket(packet.errorResult("cancel", null, "internal-server-error",
														"Address of streamhost not found", false));
					}
				} else {
					String sid = query.getAttributeStaticStr("sid");

					if (sid != null) {

						// Generate stream unique id
						String cid = createConnId(sid, packet.getStanzaFrom().toString(),
												  query.getCDataStaticStr(QUERY_ACTIVATE_PATH));

						if (cid == null) {
							addOutPacket(packet.errorResult("cancel", null, "internal-server-error", null, false));
						}

						Stream stream = getStream(cid);

						if (stream != null) {
							stream.setRequester(packet.getStanzaFrom());
							stream.setTarget(JID.jidInstance(query.getCDataStaticStr(QUERY_ACTIVATE_PATH)));
							if (!verifier.isAllowed(stream)) {
								stream.close();
								addOutPacket(packet.errorResult("cancel", null, "not-allowed", null, false));

								return;
							}

							// Let's try to activate stream
							if (!stream.activate()) {
								stream.close();
								addOutPacket(packet.errorResult("cancel", null, "internal-server-error", null, false));

								return;
							}
							addOutPacket(packet.okResult((Element) null, 0));
						} else if (!sendToNextNode(packet)) {
							addOutPacket(packet.errorResult("cancel", null, "item-not-found", null, true));
						}
					} else {
						addOutPacket(packet.errorResult("cancel", null, "bad-request", null, false));
					}
				}
			} else {
				addOutPacket(packet.errorResult("cancel", 400, "feature-not-implemented", null, false));
			}
		} catch (Exception ex) {
			if (log.isLoggable(Level.FINE)) {
				log.log(Level.FINE, "exception while processing packet = " + packet, ex);
			}
			addOutPacket(packet.errorResult("cancel", null, "internal-server-error", null, false));
		}
	}

	@Override
	public boolean serviceStopped(Socks5IOService<?> serv) {
		try {
			verifier.updateTransfer(serv, true);
		} catch (TigaseDBException ex) {
			log.log(Level.WARNING, "problem during accessing database ", ex);
		} catch (QuotaException ex) {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, ex.getMessage(), ex);
			}
		}

		return super.serviceStopped(serv);
	}

	@Override
	public void socketDataProcessed(Socks5IOService service) {
		try {
			verifier.updateTransfer(service, false);
			super.socketDataProcessed(service);
		} catch (Socks5Exception ex) {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "stopping service after exception from verifier: " + ex.getMessage());
			}

			// @todo send error
			Packet message = Message.getMessage(getComponentId(), service.getJID(), StanzaType.error, ex.getMessage(),
												null, null, null);

			this.addOutPacket(message);
			service.forceStop();
		} catch (TigaseDBException ex) {
			Logger.getLogger(Socks5ProxyComponent.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	//~--- get methods ----------------------------------------------------------

	/**
	 * Returns disco category
	 *
	 * @return
	 */
	public String getDiscoCategory() {
		return "proxy";
	}

	/**
	 * Returns disco category type
	 *
	 * @return
	 */
	@Override
	public String getDiscoCategoryType() {
		return "bytestreams";
	}

	/**
	 * Returns disco description
	 *
	 * @return
	 */
	@Override
	public String getDiscoDescription() {
		return "Socks5 Bytestreams Service";
	}

	/**
	 * Return Socks5 repository
	 *
	 * @return
	 */
	public Socks5Repository getSock5Repository() {
		return socks5_repo;
	}

	@Override
	public void initialize() {
		super.initialize();

		updateServiceDiscoveryItem(getName(), null, getDiscoDescription(), getDiscoCategory(), getDiscoCategoryType(),
								   false, XMLNS_BYTESTREAMS);
	}

	//~--- set methods ----------------------------------------------------------

	@Override
	public void setClusterController(ClusterControllerIfc cl_controller) {
		clusterController = cl_controller;
		clusterController.removeCommandListener(packetForwardCmd);
		clusterController.setCommandListener(packetForwardCmd);
	}

	//~--- methods --------------------------------------------------------------

	/**
	 * Send to next node if there is any available
	 *
	 * @param fromNode
	 * @param visitedNodes
	 * @param data
	 * @param packet
	 *
	 * @return
	 *
	 * @throws TigaseStringprepException
	 */
	protected boolean sendToNextNode(JID fromNode, Set<JID> visitedNodes, Map<String, String> data, Packet packet)
			throws TigaseStringprepException {
		JID next_node = null;
		List<JID> nodes = cluster_nodes;

		if (nodes != null) {
			for (JID node : nodes) {
				if (!visitedNodes.contains(node) && !getComponentId().equals(node)) {
					next_node = node;

					break;
				}
			}
		}
		if (next_node != null) {
			clusterController.sendToNodes(PACKET_FORWARD_CMD, packet.getElement(), fromNode, visitedNodes,
										  new JID[]{next_node});
		}

		return next_node != null;
	}

	/**
	 * Send to next node if there is any available
	 *
	 * @param packet
	 *
	 * @return
	 */
	protected boolean sendToNextNode(Packet packet) {
		if (cluster_nodes.size() > 0) {
			JID cluster_node = getFirstClusterNode(packet.getStanzaTo());

			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Cluster node found: {0}", cluster_node);
			}
			if (cluster_node != null) {
				clusterController.sendToNodes(PACKET_FORWARD_CMD, packet.getElement(), getComponentId(), null,
											  cluster_node);

				return true;
			}

			return false;
		}

		return false;
	}

	//~--- get methods ----------------------------------------------------------

	/**
	 * Returns array of default ports
	 *
	 * @return
	 */
	@Override
	protected int[] getDefaultPorts() {
		return new int[]{1080};
	}

	/**
	 * Returns first node of cluster
	 *
	 * @param userJid
	 *
	 * @return
	 */
	protected JID getFirstClusterNode(JID userJid) {
		JID cluster_node = null;
		List<JID> nodes = cluster_nodes;

		if (nodes != null) {
			for (JID node : nodes) {
				if (!node.equals(getComponentId())) {
					cluster_node = node;

					break;
				}
			}
		}

		return cluster_node;
	}

	//~--- methods --------------------------------------------------------------

	/**
	 * Creates unique stream id generated from sid, from and to
	 *
	 * @param sid
	 * @param from
	 * @param to
	 *
	 * @return
	 */
	private String createConnId(String sid, String from, String to) {
		try {
			String id = sid + from + to;
			MessageDigest md = MessageDigest.getInstance("SHA-1");

			return Algorithms.hexDigest("", id, "SHA-1");
		} catch (NoSuchAlgorithmException e) {
			log.warning(e.getMessage());

			return null;
		}
	}

	//~--- inner classes --------------------------------------------------------

	/**
	 * Handles forward command used to forward packet to another node of cluster
	 */
	private class PacketForward
			extends CommandListenerAbstract {

		public PacketForward() {
			super(PACKET_FORWARD_CMD, Priority.HIGH);
		}

		@Override
		public void executeCommand(JID fromNode, Set<JID> visitedNodes, Map<String, String> data,
								   Queue<Element> packets) throws ClusterCommandException {
			for (Element el_packet : packets) {
				try {
					Packet packet = Packet.packetInstance(el_packet);

					packet.setPacketFrom(fromNode);
					packet.setPacketTo(getComponentId());

					String cid = createConnId(el_packet.getAttributeStaticStr(Iq.IQ_QUERY_PATH, "sid"),
											  el_packet.getAttributeStaticStr(Packet.FROM_ATT),
											  el_packet.getCDataStaticStr(IQ_QUERY_ACTIVATE_PATH));

					if (cid == null) {
						addOutPacket(Authorization.INTERNAL_SERVER_ERROR.getResponseMessage(packet,
																							"Could not calculate SID",
																							true));

						continue;
					}
					if (hasStream(cid)) {
						processPacket(packet);
					} else if (!sendToNextNode(fromNode, visitedNodes, data, packet)) {
						addOutPacket(packet.errorResult("cancel", null, "item-not-found", null, true));
					}
				} catch (PacketErrorTypeException ex) {
					Logger.getLogger(Socks5ProxyComponent.class.getName()).log(Level.SEVERE, null, ex);
				} catch (TigaseStringprepException ex) {
					log.log(Level.WARNING, "Addressing error, stringprep failure: {0}", el_packet);
				}
			}
		}
	}
}

//~ Formatted in Tigase Code Convention on 13/10/15
