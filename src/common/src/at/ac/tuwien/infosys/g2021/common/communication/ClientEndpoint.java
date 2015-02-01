package at.ac.tuwien.infosys.g2021.common.communication;

import at.ac.tuwien.infosys.g2021.common.BufferConfiguration;
import at.ac.tuwien.infosys.g2021.common.BufferState;
import at.ac.tuwien.infosys.g2021.common.SimpleData;
import at.ac.tuwien.infosys.g2021.common.communication.jaxb.BufferConfigurationTag;
import at.ac.tuwien.infosys.g2021.common.communication.jaxb.BufferMetainfoTag;
import at.ac.tuwien.infosys.g2021.common.communication.jaxb.BufferNamesTag;
import at.ac.tuwien.infosys.g2021.common.communication.jaxb.Message;
import at.ac.tuwien.infosys.g2021.common.communication.jaxb.MetainfoTag;
import at.ac.tuwien.infosys.g2021.common.communication.jaxb.PushTag;
import at.ac.tuwien.infosys.g2021.common.util.Loggers;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is the connection endpoint at a JVM containing GBots with its DataPoint instances. It is implemented as singleton, because there is
 * only one TCP/IP connection to the daemon per JVM necessary.
 * <p/>
 */
public class ClientEndpoint {

    /** This inner class is a thread listening for messages from the daemon. */
    private class Receiver extends Thread {

        /** Initialization. */
        Receiver() {
            super("message receiver thread");
            setDaemon(true);
            start();
        }

        /**
         * Returns the answer to the waiting thread.
         *
         * @param answer the answer
         */
        private void returnAnswer(Answer answer) {

            try {
                synchronizer.exchange(answer, CommunicationSettings.CLIENT_READY_TIMEOUT, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e) {
                // This is a shutdown request. All right.
            }
            catch (TimeoutException e) {
                logger.warning("Unable to return an answer from the buffer daemon.");

                // The missing delivery of a daemon message will cause a communication breakdown. We close the connection.
                disconnectImmediately();
            }
        }

        /** The thread implementation it runs unless the socket is closed. It reads all the messages received and interpret them. */
        @Override
        public void run() {

            // A local copy of the connection to be thread-safe
            Connection daemon = connection;

            if (daemon != null) logger.fine("The receiver thread for daemon messages is started.");

            try {
                JAXBInterface jaxb = new JAXBInterface();

                while (daemon != null && !interrupted()) {

                    Message message = daemon.receive();

                    // Interpret the received message and create an answer.
                    if (message.getAccepted() != null) {
                        logger.fine("An 'Accept' message has been received from the buffer daemon.");
                        returnAnswer(new Answer(true));
                    }
                    else if (message.getBufferConfiguration() != null) {
                        logger.fine("A 'BufferConfiguration' message has been received from the buffer daemon.");

                        BufferConfigurationTag bufferConfiguration = message.getBufferConfiguration();
                        returnAnswer(new Answer(jaxb.configurationFromXML(bufferConfiguration)));
                    }
                    else if (message.getBufferMetainfo() != null) {
                        logger.fine("A 'BufferMetainfo' message has been received from the buffer daemon.");

                        BufferMetainfoTag bufferMetainfo = message.getBufferMetainfo();
                        Map<String, String> metainfo = new TreeMap<>();

                        for (MetainfoTag tag : bufferMetainfo.getMetainfo()) metainfo.put(tag.getName(), tag.getInfo());

                        returnAnswer(new Answer(metainfo));
                    }
                    else if (message.getBufferNames() != null) {

                        BufferNamesTag bufferNames = message.getBufferNames();
                        Set<String> names = new TreeSet<>(bufferNames.getName());

                        logger.fine(String.format("A 'BufferNames' message with %d entries has been received from the buffer daemon.",
                                                  bufferNames.getName().size()));

                        returnAnswer(new Answer(names));
                    }
                    else if (message.getDisconnect() != null) {
                        logger.warning("A 'Disconnect' message has been received from the buffer daemon.");
                        disconnectImmediately();
                    }
                    else if (message.getEstablish() != null) {
                        logger.warning("An 'Establish' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else if (message.getGet() != null) {
                        logger.warning("A 'Get' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else if (message.getGetBufferConfiguration() != null) {
                        logger.warning("A 'GetBufferConfiguration' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else if (message.getGetImmediate() != null) {
                        logger.warning("A 'GetImmediate' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else if (message.getPush() != null) {
                        logger.fine("A 'Push' message has been received from the buffer daemon.");

                        PushTag push = message.getPush();
                        SimpleData data = new SimpleData(push.getName(),
                                                         push.getTimestamp().toGregorianCalendar().getTime(),
                                                         BufferState.valueOf(push.getState()),
                                                         push.getValue());
                        if (push.isSpontaneous()) fireValueChanged(data);
                        else returnAnswer(new Answer(data));
                    }
                    else if (message.getQueryBuffersByMetainfo() != null) {
                        logger.warning("A 'QueryBuffersByMetainfo' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else if (message.getQueryBuffersByName() != null) {
                        logger.warning("A 'QueryBuffersByName' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else if (message.getQueryMetainfo() != null) {
                        logger.warning("A 'QueryMetainfo' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else if (message.getRejected() != null) {
                        logger.fine("A 'Rejected' message has been received from the buffer daemon.");
                        returnAnswer(new Answer(false));
                    }
                    else if (message.getReleaseBuffer() != null) {
                        logger.warning("A 'ReleaseBuffer' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else if (message.getSet() != null) {
                        logger.warning("A 'Set' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else if (message.getSetBufferConfiguration() != null) {
                        logger.warning("A 'GetBufferConfiguration' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else if (message.getShutdown() != null) {
                        logger.warning("A 'Shutdown' message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }
                    else {
                        logger.warning("An unknown message has been received from the buffer daemon.");
                        handleProtocolViolation();
                    }

                    daemon = connection;
                }
            }
            catch (Exception e) {

                // An understandable exception, if the connection was closed.
                if (connection != null && connection.isConnected()) handleCommunicationError(e);
            }
        }

        /** Stops the receiver thread. */
        void shutdown() { interrupt(); }
    }

    // The logger.
    private final static Logger logger = Loggers.getLogger(ClientEndpoint.class);

    // This is the one existing instance of this class
    private static ClientEndpoint instance = new ClientEndpoint();

    // This is the lock for synchronization of requests sent to the daemon
    private final Lock requestSerializer;

    // This is the synchronisation object for the data exchange between calling thread and receiver thread
    private final Exchanger<Answer> synchronizer;

    // The observers
    private final WeakHashMap<ValueChangeObserver, Object> observers;

    // The receiver thread
    private Receiver receiverThread;

    // The connection to the daemon
    private Connection connection;
    private final Object connectionLock;

    // The message sender
    private MessageSender sender;

    /** Initialisation of the endpoint instance. */
    private ClientEndpoint() {

        synchronizer = new Exchanger<>();
        requestSerializer = new ReentrantLock();
        observers = new WeakHashMap<>();
        receiverThread = null;
        connection = null;
        connectionLock = new Object();
        sender = null;
    }

    /**
     * Returns the connection state of the connection to the daemon.
     *
     * @return <tt>true</tt>, if there is a TCP/IP connection established
     */
    public boolean isConnected() {

        synchronized (connectionLock) {
            return connection != null;
        }
    }

    /**
     * Establishes the connection to the daemon. Is the connection is established, any call to this
     * method is ignored.
     *
     * @throws java.io.IOException if this operation fails
     */
    public void connect() throws IOException {

        synchronized (connectionLock) {
            if (!isConnected()) {

                // Create a connected socket.
                Socket socket;

                try {
                    socket = new Socket(CommunicationSettings.bufferDaemonAddress(), CommunicationSettings.bufferDaemonPort());
                    socket.setKeepAlive(true);
                    socket.setReuseAddress(true);
                }
                catch (IOException e) {
                    logger.log(Level.WARNING,
                               String.format("Cannot connect the daemon at '%s:%d'.",
                                             CommunicationSettings.bufferDaemonAddress(),
                                             CommunicationSettings.bufferDaemonPort()),
                               e);
                    throw new IOException(e.getMessage(), e);
                }

                // Establish the connection
                connection = new Connection(socket);
                sender = new MessageSender(connection);

                // Listen for messages
                receiverThread = new Receiver();

                // Send an establish-Message and wait for an answer
                requestSerializer.lock();
                try {
                    sender.establish();
                    boolean ok = waitForAnAnswer().get();

                    if (ok) {
                        logger.info(String.format("The daemon at '%s:%d' accepts this connection.",
                                                  CommunicationSettings.bufferDaemonAddress(),
                                                  CommunicationSettings.bufferDaemonPort()));
                    }
                    else {
                        logger.warning(String.format("The daemon at '%s:%d' rejects this connection.",
                                                     CommunicationSettings.bufferDaemonAddress(),
                                                     CommunicationSettings.bufferDaemonPort()));
                        disconnect();
                    }
                }
                catch (NullPointerException np) {
                    handleDumblyDaemon();
                    throw new IOException("missing answer from the buffer daemon");
                }
                catch (ClassCastException cc) {
                    handleProtocolViolation();
                    throw new ProtocolException("protocol violation", cc);
                }
                catch (Exception ex) {
                    handleCommunicationError(ex);
                    throw new IOException("communication error", ex);
                }
                finally {
                    requestSerializer.unlock();
                }
            }
        }
    }

    /**
     * Closes the connection to the daemon without sending a disconnect message. Is the connection is not
     * established, any call to this method is ignored.
     */
    private void disconnectImmediately() {

        synchronized (connectionLock) {
            if (isConnected()) {

                // Close the connection
                connection.disconnect();

                // Stop the receiver thread
                receiverThread.shutdown();

                // Resetting all communication components
                receiverThread = null;
                sender = null;
                connection = null;

                // At last wie notify all about the connection shutdown
                fireCommunicationLost();
            }
        }
    }

    /**
     * Closes the connection to the daemon. Is the connection is not established, any call to this
     * method is ignored.
     */
    public void disconnect() {

        synchronized (connectionLock) {
            if (isConnected()) {

                // Sending a disconnect - Message
                if (!connection.getSocket().isClosed()) {
                    requestSerializer.lock();
                    try {
                        sender.disconnect();
                    }
                    catch (IOException io) {
                        // We are not able to communicate about the connection
                        handleCommunicationError(io);
                    }
                    finally {
                        requestSerializer.unlock();
                    }
                }

                // wait a moment to give the peer a chance to receive this message
                try {
                    Thread.sleep(100L);
                }
                catch (InterruptedException e) {
                    // Ok. Due to the close of the socket, the communication may break down
                    // and IOExceptions are thrown. This may result only in ugly log messages.
                }

                // Close the connection now
                disconnectImmediately();
            }
        }
    }

    /**
     * Waits for an answer of the buffer daemon.
     *
     * @return the answer or <tt>null</tt>, if the buffer daemon has sent no answer
     */
    private Answer waitForAnAnswer() {

        Answer result = null;

        try {
            result = synchronizer.exchange(new Answer(), CommunicationSettings.DAEMON_ANSWER_TIMEOUT, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {

            // clear the interrupt flag
            Thread.interrupted();

            logger.log(Level.WARNING,
                       String.format("The connection to the daemon at '%s:%d' has been interrupted.",
                                     CommunicationSettings.bufferDaemonAddress(),
                                     CommunicationSettings.bufferDaemonPort()),
                       e);

            // Here an interrupt may result in a communication breakdown. We close the connection.
            disconnect();
        }
        catch (TimeoutException e) {
            // The result is already right.
        }

        return result;
    }

    /** A protocol violation has occurred. The connection will be closed. */
    private void handleProtocolViolation() {

        logger.warning(String.format("The daemon at '%s:%d' uses an unknown protocol.",
                                     CommunicationSettings.bufferDaemonAddress(),
                                     CommunicationSettings.bufferDaemonPort()));
        disconnectImmediately();
    }

    /** The daemon doesn't answer. */
    private void handleDumblyDaemon() {

        logger.warning(String.format("The daemon at '%s:%d' doesn't answer.",
                                     CommunicationSettings.bufferDaemonAddress(),
                                     CommunicationSettings.bufferDaemonPort()));
        disconnectImmediately();
    }

    /**
     * There is a communication error occurred.
     *
     * @param io the causing exception
     */
    private void handleCommunicationError(Exception io) {

        logger.log(Level.WARNING,
                   String.format("Cannot communicate with the buffer daemon at '%s:%d'.",
                                 CommunicationSettings.bufferDaemonAddress(),
                                 CommunicationSettings.bufferDaemonPort()),
                   io);
        disconnectImmediately();
    }

    /**
     * <p>
     * Adds a new value change observer to the set of well known observers. If the observer argument is <tt>null</tt> or the
     * observer argument is currently registered in this object, the method call will have no effect. Note that the registered
     * observers are a set. Multiple registration will not cause multiple notifications about state or value changes.
     * </p>
     *
     * @param observer the observer
     */
    public void addValueChangeObserver(ValueChangeObserver observer) {

        synchronized (observers) {
            observers.put(observer, new Object());
        }
    }

    /**
     * <p>
     * Removes a value change observer from the set of well known observers. If the observer argument is <tt>null</tt> or the
     * observer argument is not registered in this object, the method call will have no effect.
     * </p>
     *
     * @param observer the observer
     */
    public void removeValueChangeObserver(ValueChangeObserver observer) {

        // If there is no more instance to receive values, the daemon connection is closed
        synchronized (observers) {
            observers.remove(observer);
            if (observers.size() == 0) disconnect();
        }
    }

    /**
     * Gets all currently registered observers.
     *
     * @return the observers
     */
    private Collection<ValueChangeObserver> getObservers() {

        Collection<ValueChangeObserver> result = new ArrayList<>();

        synchronized (observers) {
            result.addAll(observers.keySet());
        }

        return result;
    }

    /** Notifies any listening ValueChangeObserver about a lost connection. */
    private void fireCommunicationLost() { for (ValueChangeObserver observer : getObservers()) observer.communicationLost(); }

    /**
     * Notifies any listening ValueChangeObserver about a value change.
     *
     * @param value the new value
     */
    private void fireValueChanged(SimpleData value) { for (ValueChangeObserver observer : getObservers()) observer.valueChanged(value); }

    /**
     * This method sends a shutdown message.
     *
     * @return true, if the shutdown message was sent.
     */
    public boolean shutdown() {

        boolean result = false;

        requestSerializer.lock();
        try {
            sender.shutdown();
            result = true;
        }
        catch (Exception io) {
            handleCommunicationError(io);
        }
        finally {
            requestSerializer.unlock();
        }

        return result;
    }

    /**
     * This method looks for available buffers.
     *
     * @param name a regular expression specifying the buffer name, which should be scanned. A simple match satisfy
     *             the search condition, the regular expression must not match the whole feature description.
     *
     * @return a collection of the names of all the buffers matching this query
     */
    public Set<String> queryBuffersByName(String name) {

        Set<String> result = new HashSet<>();

        requestSerializer.lock();
        try {
            sender.queryBuffersByName(name);
            result = waitForAnAnswer().get();
        }
        catch (ClassCastException cc) {
            // The result is correct.
        }
        catch (NullPointerException np) {
            handleDumblyDaemon();
        }
        catch (Exception io) {
            handleCommunicationError(io);
        }
        finally {
            requestSerializer.unlock();
        }

        return result;
    }

    /**
     * This method looks for available buffers.
     *
     * @param topic   a regular expression specifying the buffer topics, which should be scanned for the wanted features. A simple match satisfy
     *                the search condition, the regular expression must not match the whole topic name.
     * @param feature a regular expression specifying the buffer features, which should be scanned. A simple match satisfy
     *                the search condition, the regular expression must not match the whole feature description.
     *
     * @return a collection of the names of all the buffers matching this query
     */
    public Set<String> queryBuffersByMetainfo(String topic, String feature) {

        Set<String> result = new HashSet<>();

        requestSerializer.lock();
        try {
            sender.queryBuffersByMetainfo(topic, feature);
            result = waitForAnAnswer().get();
        }
        catch (ClassCastException cc) {
            // The result is correct.
        }
        catch (NullPointerException np) {
            handleDumblyDaemon();
        }
        catch (Exception io) {
            handleCommunicationError(io);
        }
        finally {
            requestSerializer.unlock();
        }

        return result;
    }

    /**
     * This method returns the meta information of a buffer.
     *
     * @param name the name of a buffer
     *
     * @return the buffer meta information, which is <tt>null</tt> if the buffer doesn't exists or the connection to the daemon is broken
     */
    public Map<String, String> queryMetainfo(String name) {

        Map<String, String> result = null;

        requestSerializer.lock();
        try {
            sender.queryMetainfo(name);
            result = waitForAnAnswer().get();
        }
        catch (ClassCastException cc) {
            // The result is correct.
        }
        catch (NullPointerException np) {
            handleDumblyDaemon();
        }
        catch (Exception io) {
            handleCommunicationError(io);
        }
        finally {
            requestSerializer.unlock();
        }

        return result;
    }

    /**
     * Get the current configuration data of a buffer.
     *
     * @param bufferName the name of the buffer
     *
     * @return the current buffer configuration
     *
     * @throws java.lang.IllegalArgumentException
     *          there is no buffer with the given buffer name
     */
    public BufferConfiguration getBufferConfiguration(String bufferName) throws IllegalArgumentException {

        requestSerializer.lock();
        try {
            sender.getBufferConfiguration(bufferName);
            return waitForAnAnswer().get();
        }
        catch (ClassCastException cc) {
            throw new IllegalArgumentException("unknown buffer " + bufferName);
        }
        catch (NullPointerException np) {
            handleDumblyDaemon();
            return null;
        }
        catch (Exception io) {
            handleCommunicationError(io);
            return null;
        }
        finally {
            requestSerializer.unlock();
        }
    }

    /**
     * Changes the current configuration of a buffer.
     *
     * @param bufferName    the name of the buffer
     * @param configuration the new buffer configuration
     * @param createAllowed is the creation of a new buffer allowed
     *
     * @return <tt>true</tt>, if the configuration was successfully changed in the daemon
     */
    public boolean setBufferConfiguration(String bufferName, BufferConfiguration configuration, boolean createAllowed) {

        requestSerializer.lock();
        try {
            sender.setBufferConfiguration(bufferName, configuration, createAllowed);
            return waitForAnAnswer().get();
        }
        catch (NullPointerException np) {
            handleDumblyDaemon();
            return false;
        }
        catch (Exception io) {
            handleCommunicationError(io);
            return false;
        }
        finally {
            requestSerializer.unlock();
        }
    }

    /**
     * Changes the current configuration of a buffer.
     *
     * @param bufferName the name of the buffer
     *
     * @return <tt>true</tt>, if the configuration was successfully changed in the daemon
     *
     * @throws java.lang.IllegalArgumentException
     *          there is no buffer with the given buffer name
     */
    public boolean releaseBuffer(String bufferName) throws IllegalArgumentException {

        requestSerializer.lock();
        try {
            sender.releaseBuffer(bufferName);
            return waitForAnAnswer().get();
        }
        catch (NullPointerException np) {
            handleDumblyDaemon();
            return false;
        }
        catch (Exception io) {
            handleCommunicationError(io);
            return false;
        }
        finally {
            requestSerializer.unlock();
        }
    }

    /**
     * Get the current information about a buffer attached to this data point.
     *
     * @param bufferName the name of the buffer
     *
     * @return the current buffer data
     *
     * @throws java.lang.IllegalArgumentException
     *          there is no buffer with the given buffer name assigned to this data point
     */
    public SimpleData getImmediate(String bufferName) throws IllegalArgumentException {

        requestSerializer.lock();
        try {
            sender.getImmediate(bufferName);
            return waitForAnAnswer().get();
        }
        catch (ClassCastException cc) {
            throw new IllegalArgumentException("unknown buffer " + bufferName);
        }
        catch (NullPointerException np) {
            handleDumblyDaemon();
            return new SimpleData(bufferName, new Date(), BufferState.FAULTED);
        }
        catch (Exception io) {
            handleCommunicationError(io);
            return new SimpleData(bufferName, new Date(), BufferState.FAULTED);
        }
        finally {
            requestSerializer.unlock();
        }
    }

    /**
     * Get the current information about a buffer attached to this data point.
     *
     * @param bufferName the name of the buffer
     */
    public void getOnChange(String bufferName) {

        try {
            sender.get(bufferName);
        }
        catch (IOException io) {
            handleCommunicationError(io);
        }
    }

    /**
     * Sets the value to an actor assigned to this data point.
     *
     * @param bufferName the name of the actor buffer
     * @param value      the new value
     *
     * @return the current actor value
     *
     * @throws IllegalStateException if the buffer isn't an actor or the actor isn't in the state <tt>{@link at.ac.tuwien.infosys.g2021.common.BufferState#READY}</tt>.
     */
    public SimpleData set(String bufferName, Number value) throws IllegalStateException {

        requestSerializer.lock();
        try {
            sender.set(bufferName, value.doubleValue());
            return waitForAnAnswer().get();
        }
        catch (ClassCastException cc) {
            throw new IllegalStateException("value change rejected");
        }
        catch (NullPointerException np) {
            handleDumblyDaemon();
            return new SimpleData(bufferName, new Date(), BufferState.FAULTED);
        }
        catch (Exception io) {
            handleCommunicationError(io);
            return new SimpleData(bufferName, new Date(), BufferState.FAULTED);
        }
        finally {
            requestSerializer.unlock();
        }
    }

    /**
     * Reads the client endpoint instance.
     *
     * @return the client endpoint
     */
    public static ClientEndpoint get() { return instance; }
}
