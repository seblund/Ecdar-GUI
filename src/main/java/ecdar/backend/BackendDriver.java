package ecdar.backend;

import EcdarProtoBuf.ComponentProtos;
import EcdarProtoBuf.EcdarBackendGrpc;
import EcdarProtoBuf.QueryProtos;
import ecdar.Ecdar;
import ecdar.abstractions.Component;
import ecdar.abstractions.QueryState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import javafx.util.Pair;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BackendDriver {
    private final Pair<AtomicInteger, AtomicInteger> numberOfReveaalSockets = new Pair<>(new AtomicInteger(0), new AtomicInteger(5));
    private final List<QuerySocket> reveaalSockets = new CopyOnWriteArrayList<>();

    private final Pair<AtomicInteger, AtomicInteger> numberOfJEcdarSockets = new Pair<>(new AtomicInteger(0), new AtomicInteger(5));
    private final List<QuerySocket> jEcdarSockets = new CopyOnWriteArrayList<>();

    private final Queue<ExecutableQuery> waitingQueries = new LinkedList<>();

    private Integer globalPortNumber = 5162;

    private final ArrayList<Process> backendProcesses = new ArrayList<>();

    public BackendDriver() {
        new Thread(() -> {
            while(true) {
                // Try to read from all Reveaal instances that are executing a query and have outputted something
//                for (QuerySocket socket : reveaalSockets) {
//                    try {
//                        if (socket.isRunningQuery() && socket.getReader().ready()) readFromSocket(socket);
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                }
//
//                // Try to read from all jEcdar instances that are executing a query and have outputted something
//                for (QuerySocket socket : jEcdarSockets) {
//                    try {
//                        if (socket.isRunningQuery() && socket.getReader().ready()) readFromSocket(socket);
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                }

                // Execute the next waiting query
                ExecutableQuery query = waitingQueries.poll();
                if (query != null) query.execute();

                // Currently necessary in order not to crash the program
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    return;
                }
            }
        }).start();
    }

    public void addQueryToExecutionQueue(String query, BackendHelper.BackendNames backend, Consumer<Boolean> success, Consumer<BackendException> failure, QueryListener queryListener) {
        waitingQueries.add(new ExecutableQuery(query, backend, success, failure, queryListener));
    }

    public Pair<ArrayList<String>, ArrayList<String>> getInputOutputs(String query) {
//        if (!query.startsWith("refinement")) {
//            return null;
//        }
//
//        // Pair is used as a tuple, not a key-value pair
//        Pair<ArrayList<String>, ArrayList<String>> inputOutputs = new Pair<>(new ArrayList<>(), new ArrayList<>());
//
//        ProcessBuilder pb = new ProcessBuilder("src/Reveaal", "-c", Ecdar.projectDirectory.get(), query.replaceAll("\\s", ""));
//        pb.redirectErrorStream(true);
//        try {
//            //Start the j-Ecdar process
//            Process ReveaalEngineInstance = pb.start();
//
//            //Communicate with the j-Ecdar process
//            try (
//                    var ReveaalReader = new BufferedReader(new InputStreamReader(ReveaalEngineInstance.getInputStream()));
//            ) {
//                //Read the result of the query from the j-Ecdar process
//                String line;
//                while ((line = ReveaalReader.readLine()) != null) {
//                    // Process the query result
//                    if (line.endsWith("extra inputs")){
//                        Matcher m = Pattern.compile("[\"]([^\"]+)[\"]").matcher(line);
//                        while(m.find()){
//                            inputOutputs.getKey().add(m.group(1));
//                        }
//                    } else if (line.startsWith("extra outputs")) {
//                        Matcher m = Pattern.compile("[\"]([^\"]+)[\"]").matcher(line);
//                        while(m.find()){
//                            inputOutputs.getValue().add(m.group(1));
//                        }
//                    }
//                }
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//
        return null;//inputOutputs;
    }

    public void closeAllSockets() throws IOException {
        for (QuerySocket s : reveaalSockets) s.close();
        for (QuerySocket s : jEcdarSockets) s.close();
        for (Process p : backendProcesses) p.destroy(); // ToDo NIELS: Should be forcibly maybe?
    }

    public void setMaxNumberOfSockets(int i) {
        numberOfReveaalSockets.getValue().set(i);
        numberOfJEcdarSockets.getValue().set(i);

        // ToDo NIELS: Potentially stop sockets until within new range [0, i]
    }

    public int getMaxNumberOfSockets() {
        return numberOfReveaalSockets.getValue().get();
    }

    private void readFromSocket(QuerySocket socket) throws IOException {
        String line;
        System.out.println("Starting read");
        while (socket.getReader().ready() && !(line = socket.getReader().readLine()).isEmpty()) {
            System.out.println(line);
            // Skip the returned query and possible notes, as these should not be shown in the GUI
            if (line.startsWith("note:")) {
                continue;
            }

            // Process the query result
            if ((line.endsWith("true") || line.startsWith("Query: Query")) && (socket.getExecutableQuery().queryListener.getQuery().getQueryState().getStatusCode() <= QueryState.SUCCESSFUL.getStatusCode())) {
                socket.getExecutableQuery().queryListener.getQuery().setQueryState(QueryState.SUCCESSFUL);
                socket.getExecutableQuery().success.accept(true);
            } else if (line.endsWith("result: false")) {
                socket.getExecutableQuery().queryListener.getQuery().setQueryState(QueryState.ERROR);
                socket.getExecutableQuery().success.accept(false);
            } else {
                socket.getExecutableQuery().queryListener.getQuery().setQueryState(QueryState.SYNTAX_ERROR);
                socket.getExecutableQuery().failure.accept(new BackendException.QueryErrorException(line));
            }

            // ToDo NIELS: Maybe just trigger poll() on query queue here instead of while loop (would require some initial poll technique)
        }

        socket.getExecutableQuery().success.accept(true);
        socket.executableQuery = null;
    }

    private void executeQuery(ExecutableQuery executableQuery) {
        if(executableQuery.queryListener.getQuery().getQueryState() == QueryState.UNKNOWN) return;

        // Get available socket or start new
        Optional<QuerySocket> socket;
        if (executableQuery.backend.equals(BackendHelper.BackendNames.jEcdar)) {
            socket = jEcdarSockets.stream().filter((element) -> !element.isRunningQuery()).findFirst();
        } else {
            socket = reveaalSockets.stream().filter((element) -> !element.isRunningQuery()).findFirst();
        }

        QuerySocket querySocket = socket.orElseGet(() -> (executableQuery.backend == BackendHelper.BackendNames.Reveaal
                ? startNewQuerySocket(BackendHelper.BackendNames.Reveaal, numberOfReveaalSockets, reveaalSockets)
                : startNewQuerySocket(BackendHelper.BackendNames.jEcdar, numberOfJEcdarSockets, jEcdarSockets)));

        // If the query socket is null, there are no available sockets
        // and the maximum number of sockets has already been reached
        if (querySocket == null) {
            waitingQueries.add(executableQuery);
            return;
        }

        querySocket.executeQuery(executableQuery);
    }

    private QuerySocket startNewQuerySocket(BackendHelper.BackendNames backend, Pair<AtomicInteger, AtomicInteger> numberOfSockets, List<QuerySocket> querySockets) {
        if (numberOfSockets.getKey().get() < numberOfSockets.getValue().get()) {
            try {
                while (true) {
                    globalPortNumber += 1;

                    ProcessBuilder pb;
                    if (backend.equals(BackendHelper.BackendNames.jEcdar)) {
                        pb = new ProcessBuilder("java", "-jar", "src/libs/j-Ecdar.jar");
                    } else {
                        pb = new ProcessBuilder("src/Reveaal", "-p", "127.0.0.1:" + globalPortNumber).inheritIO();
                    }

                    Process p = pb.start();
                    if (p.isAlive()) {
                        // If the process is not alive, the process failed while starting up, try again ToDo NIELS: This is caused by Socket or ConnectionRefused exceptions, so a better way of handling this is needed
                        backendProcesses.add(p);
                        break;
                    }
                }

                QuerySocket newSocket = new QuerySocket(new Socket((String) null, globalPortNumber, null, 0));

                querySockets.add(newSocket);
                numberOfSockets.getKey().getAndIncrement();

                return newSocket;

            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("Max number of sockets already reached");
        }

        return null;
    }

    private class ExecutableQuery {
        private final String query;
        private final BackendHelper.BackendNames backend;
        private final Consumer<Boolean> success;
        private final Consumer<BackendException> failure;
        private final QueryListener queryListener;

        ExecutableQuery(String query, BackendHelper.BackendNames backend, Consumer<Boolean> success, Consumer<BackendException> failure, QueryListener queryListener) {
            this.query = query;
            this.backend = backend;
            this.success = success;
            this.failure = failure;
            this.queryListener = queryListener;
        }

        public void execute() {
            executeQuery(this);
        }
    }

    private class QuerySocket {
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private final Socket socket;
        private ExecutableQuery executableQuery = null;

        QuerySocket(Socket socket) throws IOException {
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            this.socket = socket;

            socket.close();
        }

        public BufferedReader getReader() {
            return reader;
        }

        public BufferedWriter getWriter() {
            return writer;
        }

        public ExecutableQuery getExecutableQuery() {
            return executableQuery;
        }

        public void executeQuery(ExecutableQuery executableQuery) {
            this.executableQuery = executableQuery;

            ManagedChannel channel = ManagedChannelBuilder.forTarget(socket.getInetAddress().toString() + ":" + socket.getPort()).usePlaintext().build();

            System.out.println("Channel: " + channel);

            EcdarBackendGrpc.EcdarBackendBlockingStub stub = EcdarBackendGrpc.newBlockingStub(channel);

            var componentsBuilder = QueryProtos.ComponentsUpdateRequest.newBuilder();

            for (Component c : Ecdar.getProject().getComponents()) {
                componentsBuilder.addComponents(ComponentProtos.Component.newBuilder().setJson(c.serialize().toString()).build());
            }

            System.out.println(stub.updateComponents(componentsBuilder.build()));

            QueryProtos.QueryResponse result = stub.sendQuery(QueryProtos.Query.newBuilder().setId(0).setQuery(executableQuery.query).build());

            System.out.println(result.getResultCase().getNumber());
        }

        public boolean isRunningQuery() {
            return executableQuery != null;
        }

        public void close() throws IOException {
            socket.close();

            // Remove the socket from the socket list
            if (jEcdarSockets.remove(this)) {
                numberOfJEcdarSockets.getKey().getAndDecrement();
            } else if(reveaalSockets.remove(this)) {
                numberOfReveaalSockets.getKey().getAndDecrement();
            } else {
                // ToDo NIELS: Somehow the socket was not present in either list
                System.out.println("The socket was not found in the socket lists");
            }
        }

    }

    enum TraceType {
        NONE, SOME, SHORTEST, FASTEST;


        @Override
        public String toString() {
            return "trace " + this.ordinal();
        }
    }
}
