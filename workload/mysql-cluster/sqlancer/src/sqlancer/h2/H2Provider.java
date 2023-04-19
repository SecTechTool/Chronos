package sqlancer.h2;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import com.google.auto.service.AutoService;

import sqlancer.AbstractAction;
import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.SQLGlobalState;
import sqlancer.SQLProviderAdapter;
import sqlancer.StatementExecutor;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryProvider;
import sqlancer.h2.H2Provider.H2GlobalState;

@AutoService(DatabaseProvider.class)
public class H2Provider extends SQLProviderAdapter<H2GlobalState, H2Options> {

    public H2Provider() {
        super(H2GlobalState.class, H2Options.class);
    }

    public enum Action implements AbstractAction<H2GlobalState> {

        INSERT(H2InsertGenerator::getQuery), //
        INDEX(H2IndexGenerator::getQuery), //
        ANALYZE((g) -> new SQLQueryAdapter("ANALYZE")), //
        CREATE_VIEW(H2ViewGenerator::getQuery), //
        UPDATE(H2UpdateGenerator::getQuery), //
        DELETE(H2DeleteGenerator::getQuery), //
        SET(H2SetGenerator::getQuery);

        private final SQLQueryProvider<H2GlobalState> sqlQueryProvider;

        Action(SQLQueryProvider<H2GlobalState> sqlQueryProvider) {
            this.sqlQueryProvider = sqlQueryProvider;
        }

        @Override
        public SQLQueryAdapter getQuery(H2GlobalState state) throws Exception {
            return sqlQueryProvider.getQuery(state);
        }
    }

    private static int mapActions(H2GlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        switch (a) {
        case INSERT:
            return r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
        case ANALYZE:
            return r.getInteger(0, 5);
        case INDEX:
        case SET:
            return r.getInteger(0, 5);
        case CREATE_VIEW:
            return r.getInteger(0, 2);
        case UPDATE:
        case DELETE:
            return r.getInteger(0, 10);
        default:
            throw new AssertionError(a);
        }
    }

    public static class H2GlobalState extends SQLGlobalState<H2Options, H2Schema> {

        @Override
        protected H2Schema readSchema() throws SQLException {
            return H2Schema.fromConnection(getConnection(), getDatabaseName());
        }

    }

    @Override
    public void generateDatabase(H2GlobalState globalState) throws Exception {
        if (Randomly.getBoolean()) {
            H2SetGenerator.getQuery(globalState).execute(globalState);
        }
        boolean success;
        for (int i = 0; i < Randomly.fromOptions(1, 2, 3); i++) {
            do {
                SQLQueryAdapter qt = new H2TableGenerator().getQuery(globalState);
                success = globalState.executeStatement(qt);
            } while (!success);
        }
        StatementExecutor<H2GlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                H2Provider::mapActions, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();
    }

    @Override
    public SQLConnection createDatabase(H2GlobalState globalState) throws SQLException {
        String connectionString = "jdbc:h2:~/" + globalState.getDatabaseName() + ";DB_CLOSE_ON_EXIT=FALSE";
        Connection connection = DriverManager.getConnection(connectionString, "sa", "");
        connection.createStatement().execute("DROP ALL OBJECTS DELETE FILES");
        connection.close();
        connection = DriverManager.getConnection(connectionString, "sa", "");
        return new SQLConnection(connection);
    }

    @Override
    public String getDBMSName() {
        return "h2";
    }

}
