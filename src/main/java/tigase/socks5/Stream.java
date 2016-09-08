package tigase.socks5;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import tigase.xmpp.BareJID;
import tigase.xmpp.JID;


public class Stream {
	
        private static final Logger log = Logger.getLogger(Stream.class.getCanonicalName());
        
	private final Socks5IOService[] conns = new Socks5IOService[2];
        private final Socks5ConnectionManager manager;
        
	private final String sid;
        private JID requester = null;
        private JID target = null;
        private final Map<String,Object> data;
	
	public Stream(String sid, Socks5ConnectionManager manager) {
		this.sid = sid;
                this.manager = manager;
                this.data = new HashMap<String,Object>();
	}
	
        /**
         * Returns stream id
         * 
         * @return 
         */        
        public String getSID() {
		return sid;
	}
        
        /**
         * Set bare JID of requester
         * 
         * @param requester 
         */
        public void setRequester(JID requester) {
                this.requester = requester;
        }

        /**
         * Get bare JID of requester
         * 
         * @param requester 
         */
        public JID getRequester() {
                return requester;
        }

        /**
         * Set bare JID of target
         * 
         * @param requester 
         */
        public void setTarget(JID target) {
                this.target = target;
        }
        
        /**
         * Get bare JID of target
         * 
         * @param requester 
         */
        public JID getTarget() {
                return target;
        }
       	
        /**
         * Set stream data
         * 
         * @param key
         * @param value 
         */
        public void setData(String key, Object value) {
                this.data.put(key, value);
        }
        
        /**
         * Returns stream data for key
         * 
         * @param key
         * @return 
         */
        public Object getData(String key) {
                return this.data.get(key);
        }
        
        /**
         * Assign connection to stream
         * 
         * @param con 
         */
	public void addConnection(Socks5IOService con) {
		int i=0;
                con.setStream(this);
		
                if(conns[i] != null)
			i++;
                
                con.setSocks5ConnectionType(i==1 ? Socks5ConnectionType.Requester : Socks5ConnectionType.Target);
		conns[i] = con;
	}

        /**
         * Returns connection with specified type
         * 
         * @param connectionType
         * @return 
         */
        public Socks5IOService getConnection(Socks5ConnectionType connectionType) {
                return conns[connectionType == Socks5ConnectionType.Requester ? 1 : 0];
        }
        
        /**
         * Forward data to another service
         * 
         * @param buf
         * @param con
         * @throws IOException 
         */
	public void proxy(ByteBuffer buf, Socks5IOService con) throws IOException {
		if(conns[0] == con) {
			con = conns[1];
		}
		else {
			con = conns[0];
		}
        
//                if (log.isLoggable(Level.FINEST)) {
//                        log.log(Level.FINEST, "writing data to connection = {0}", con);
//                }
        if (!con.waitingToSend()) {
	        con.writeBytes(buf);
//        } else {
        }
		buf.compact();
	}
	
        /**
         * Tries to activate stream and each of service
         * 
         * @return 
         */
	public boolean activate() {
		if(conns[0] == null || conns[1] == null)
			return false;
		
		conns[0].activate();
		conns[1].activate();
		return true;
	}
	
        /**
         * Close stream
         */
	public void close() {
                int bytesRead = 0;
                
                manager.unregisterStream(this);
           
		for(int i=0; i<conns.length; i++) {
                        if (conns[i] != null) {
                                bytesRead += conns[i].getBytesReceived();
                                if (!conns[i].waitingToSend()) {
                                	if (log.isLoggable(Level.FINEST)) {
		                                log.log(Level.FINEST, "stopping connection {0}, bytes read: {1}, written: {2}", new Object[]{conns[i], conns[i].getBytesReceived(), conns[i].getBytesSent()});
	                                }
                                        conns[i].stop();
                                } else {
                                	if (log.isLoggable(Level.FINEST)) {
		                                log.log(Level.FINEST, "stopping connection after flushing {0}, bytes read: {1}, written: {2}", new Object[]{conns[i], conns[i].getBytesReceived(), conns[i].getBytesSent()});
	                                }
                                	conns[i].writeData(null);
	                                conns[i].stop();
                                }
                        }
			//conns[i] = null;			
		}
                if (log.isLoggable(Level.FINE)) {
                        log.log(Level.FINE, "stream sid = {0} transferred {1} bytes", new Object[] { toString(), bytesRead });
                }                
	}        
        
        /**
         * Returns hashCode for stream
         * 
         * @return 
         */
        public int hashCodeForStream() {
                return sid.hashCode();
        }
        
        /**
         * Returns another connections of stream
         * 
         * @param con
         * @return 
         */
        public Socks5IOService getSecondConnection(Socks5IOService con) {
                if (conns[0] == con) 
                        return conns[1];
                
                return conns[0];
        }

        /**
         * Returns bytes transferred by this stream
         * 
         * @return 
         */
        public int getTransferredBytes() {
                int bytesTransferred = 0;
                
                for (Socks5IOService con : conns) {
                        if (con != null) {
                                bytesTransferred += con.getBytesReceived();
                        }
                }
                
                return bytesTransferred;
        }
        
        @Override
        public String toString() {
                return sid;
        }
}
