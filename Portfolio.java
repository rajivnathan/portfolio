package com.ibm.hybrid.cloud.sample.portfolio;

//Standard HTTP request classes.  Maybe replace these with use of JAX-RS 2.0 client package instead...
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

//JDBC 4.0 (JSR 221)
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

//JSON-P 1.0 (JSR 353).  The replaces my old usage of IBM's JSON4J (com.ibm.json.java.JSONObject)
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

//JAX-RS 2.0 (JSR 339)
import javax.ws.rs.core.Application;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Path;


@ApplicationPath("/")
@Path("/")
/** This version stores the Portfolios via JDBC to DB2.
 *  TODO: Should update to use a DataSource, and PreparedStatements.
 */
public class Portfolio extends Application {
//	private static final String   QUOTE_SERVICE = "http://localhost:9080/stock-quote";
//	private static final String LOYALTY_SERVICE = "http://localhost:9080/loyalty-level";
	private static final String   QUOTE_SERVICE = "http://stock-quote-service:9080/stock-quote";
	private static final String LOYALTY_SERVICE = "http://loyalty-level-service:9080/loyalty-level";
	private static final String    DB2_DRIVER   = "com.ibm.db2.jcc.DB2Driver";
//	private final static String    DB2_URL      = "jdbc:db2://9.42.17.188:30287/sample"; //external Ingress URL
	private final static String    DB2_URL      = "jdbc:db2://intended-otter-db2:50000/sample"; //Kube DNS URL
	private final static String    DB2_USER     = "db2inst1";
	private final static String    DB2_PASSWORD = "password";

	public Portfolio() {
		try {
			Class.forName(DB2_DRIVER); //load our JDBC driver
		} catch (ClassNotFoundException notFound) {
			notFound.printStackTrace();
		}
	}

	@GET
	@Path("/")
	@Produces("application/json")
	public JsonArray getPortfolios() throws IOException, SQLException {
		JsonArrayBuilder builder = Json.createArrayBuilder();

		ResultSet results = invokeJDBCWithResults("SELECT * FROM Portfolio");
		while (results.next()) {
			String owner = results.getString("owner");
			double total = results.getDouble("total");
			String loyalty = results.getString("loyalty");

			JsonObjectBuilder portfolio = Json.createObjectBuilder();
			portfolio.add("owner", owner);
			portfolio.add("total", total);
			portfolio.add("loyalty", loyalty);

			builder.add(portfolio);
		}
		releaseResults(results);

		return builder.build();
	}

	@POST
	@Path("/{owner}")
	@Produces("application/json")
	public JsonObject createPortfolio(@PathParam("owner") String owner) throws SQLException {
		JsonObject portfolio = null;
		if (owner != null) {
			JsonObjectBuilder portfolioBuilder = Json.createObjectBuilder();
			portfolioBuilder.add("owner", owner);
			portfolioBuilder.add("loyalty", "Basic");
			portfolioBuilder.add("total", 0.0);

			JsonObjectBuilder stocksBuilder = Json.createObjectBuilder();
			portfolioBuilder.add("stocks", stocksBuilder);

			portfolio = portfolioBuilder.build();

			invokeJDBC("INSERT INTO Portfolio VALUES ('"+owner+"', 0.0, 'Basic')");
		}

		return portfolio;
	}

	@GET
	@Path("/{owner}")
	@Produces("application/json")
	public JsonObject getPortfolio(@PathParam("owner") String owner) throws IOException, SQLException {
		JsonObject newPortfolio = null;
		JsonObject oldPortfolio = getPortfolioWithoutStocks(owner);
		if (oldPortfolio != null) {
			String oldLoyalty = oldPortfolio.getString("loyalty");
			double overallTotal = 0;

			JsonObjectBuilder portfolio = Json.createObjectBuilder();
			portfolio.add("owner", owner);

			JsonObjectBuilder stocks = Json.createObjectBuilder();

			ResultSet results = invokeJDBCWithResults("SELECT * FROM Stock WHERE owner = '"+owner+"'");

			while (results.next()) {
				JsonObjectBuilder stock = Json.createObjectBuilder();

				String symbol = results.getString("symbol");
				stock.add("symbol", symbol);

				int shares = results.getInt("shares");
				stock.add("shares", shares);

				//call the StockQuote microservice to get the current price of this stock
				JsonObject quote = invokeREST("GET", QUOTE_SERVICE+"/"+symbol);
				String date = quote.getString("date");
				stock.add("date", date);

				double price = quote.getJsonNumber("price").doubleValue();
				stock.add("price", price);

				double total = shares * price;
				stock.add("total", total);
				overallTotal += total;

				stocks.add(symbol, stock);

				//TODO - is it OK to update rows (not adding or deleting) in the Stock table while iterating over its contents?
				invokeJDBC("UPDATE Stock SET dateQuoted = '"+date+"', price = "+price+", total = "+total+" WHERE owner = '"+owner+"' AND symbol = '"+symbol+"'");
			}

			releaseResults(results);

			portfolio.add("stocks", stocks);
			portfolio.add("total", overallTotal);

			String loyalty = null;
			try {
				//call the LoyaltyLevel microservice to get the current loyalty level of this portfolio
				JsonObject loyaltyLevel = invokeREST("GET", LOYALTY_SERVICE+"?owner="+owner+"&loyalty="+oldLoyalty+"&total="+overallTotal);
				loyalty = loyaltyLevel.getString("loyalty");
				portfolio.add("loyalty", loyalty);
			} catch (Throwable t) {
				t.printStackTrace();
			}

			invokeJDBC("UPDATE Portfolio SET total = "+overallTotal+", loyalty = '"+loyalty+"' WHERE owner = '"+owner+"'");

			newPortfolio = portfolio.build();
		}
		return newPortfolio;
	}

	private JsonObject getPortfolioWithoutStocks(String owner) throws SQLException {
		ResultSet results = invokeJDBCWithResults("SELECT * FROM Portfolio WHERE owner = '"+owner+"'");

		JsonObject portfolio = null;
		if (results.next()) {
			double total = results.getDouble("total");
			String loyalty = results.getString("loyalty");

			JsonObjectBuilder builder = Json.createObjectBuilder();
			builder.add("owner", owner);
			builder.add("total", total);
			builder.add("loyalty", loyalty);

			portfolio = builder.build();
		}

		releaseResults(results);

		return portfolio;
	}

	@PUT
	@Path("/{owner}")
	@Produces("application/json")
	public JsonObject updatePortfolio(@PathParam("owner") String owner, @QueryParam("symbol") String symbol, @QueryParam("shares") int shares) throws IOException, SQLException {
		ResultSet results = invokeJDBCWithResults("SELECT shares FROM Stock WHERE owner = '"+owner+"' and symbol = '"+symbol+"'");

		if (results.next()) { //row exists
			int oldShares = results.getInt("shares");
			releaseResults(results);

			shares += oldShares;
			if (shares > 0) {
				invokeJDBC("UPDATE Stock SET shares = "+shares+" WHERE owner = '"+owner+"' AND symbol = '"+symbol+"'");
				//getPortfolio will fill in the price, date and total
			} else {
				invokeJDBC("DELETE FROM Stock WHERE owner = '"+owner+"' AND symbol = '"+symbol+"'");
			}
		} else {
			invokeJDBC("INSERT INTO Stock (owner, symbol, shares) VALUES ('"+owner+"', '"+symbol+"', "+shares+")");
			//getPortfolio will fill in the price, date and total
		}

		//getPortfolio will fill in the overall total and loyalty

		return getPortfolio(owner);
	}

	@DELETE
	@Path("/{owner}")
	@Produces("application/json")
	public JsonObject deletePortfolio(@PathParam("owner") String owner) throws SQLException {
		JsonObject portfolio = getPortfolioWithoutStocks(owner);

		invokeJDBC("DELETE FROM Portfolio WHERE owner = '"+owner+"'");

		return portfolio; //maybe this method should return void instead?
	}

	private static JsonObject invokeREST(String verb, String uri) throws IOException {
		URL url = new URL(uri);

		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod(verb);
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setDoOutput(true);
		InputStream stream = conn.getInputStream();
//		JSONObject json = JSONObject.parse(stream); //JSON4J
		JsonObject json = Json.createReader(stream).readObject();

		stream.close();

		return json;
	}

	private void invokeJDBC(String command) throws SQLException {
		//TODO: Need to replace following line with use of a DataSource from Liberty
		Connection connection = DriverManager.getConnection(DB2_URL, DB2_USER, DB2_PASSWORD);

		Statement statement = connection.createStatement();
		statement.executeUpdate(command);

		statement.close();
		connection.close();
	}

	private ResultSet invokeJDBCWithResults(String command) throws SQLException {
		//Need to replace following line with use of a DataSource from Liberty
		Connection connection = DriverManager.getConnection(DB2_URL, DB2_USER, DB2_PASSWORD);

		Statement statement = connection.createStatement();
		statement.executeQuery(command);

		ResultSet results = statement.getResultSet();

		return results; //caller needs to pass this to releaseResults when done
	}

	private void releaseResults(ResultSet results) throws SQLException {
		Statement statement = results.getStatement();
		Connection connection = statement.getConnection();

		results.close();
		statement.close();
		connection.close();
	}
}
