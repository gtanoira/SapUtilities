package hotgo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
//import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.mail.*;
import javax.mail.internet.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
   Este programa graba diariamente las cotizaciones de ciertas monedas en una tabla en el MySql,
   para luego ser usadas por el BI de planning
   Tabla: planning_cotizacionesdiarias
   
   Este programa se ejecuta via el crontab de linux, una vez al día.
   
   El programa lee el archivo JSON:  cotizaciones_diarias.json, que le indica qué cotizaciones tiene que guardar
   diariamente en la tabla planning_cotizacionesdiarias.
   Las cotizaciones son obtenidas del SAP usando la api:  /api/exchangerate  (ej. http://clxsapjgw01:8080/ClxWebService/api/exchangerate?ratetype=M&....)
   
   El programa lee 3 archivos de configuración:
   1) settings/cotizaciones_diarias.json: contiene las cotizaciones a guardar en el MySql
   2) settings/PortalAdmin_MySql_server.json: contiene las credenciales para ubicar y acceder al MySql
   3) settings/SAPGateway_server.json: contiene la URL del server SAP Gateway donde se ejecutarán las APIs contra el SAP
   
   @param startDate (String, formato: yyyymmdd): si este parámetro es enviado al ejecutar este programa, se grabarán las cotizaciones 
                especificada por este parámetro. De ser null o undefined, se grabarán las del día de la fecha.
   @param endDate (String, formato: yyyymmdd): si este parámetro es enviado al ejecutar este programa, se grabarán las cotizaciones 
                especificada por este parámetro. De ser null o undefined, se grabarán las del día de la fecha o la fecha startDate.
                
   El programa escribe un LOG en el archivo static/cotizaciones_diarias_YYYY_MM.log con las cotizaciones a grabar y el resultado de la operación
 
*/
public class AddDailyExchangeRates {
	
	public static boolean huboErrores = false;
	
	public static void main(String[] args) throws RuntimeException, IOException {
		
		// Initialize variables
		JsonArray  currencies       = new JsonArray();
		String     outMessage       = "";
		String     outMsgErrors     = "";
		String     outMsgCurrencies = "";

		// Establecer el dia de cálculo de las cotizaciones
		DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
		ZoneId        zonedId   = ZoneId.of( "America/Argentina/Buenos_Aires" );
		ZonedDateTime startDate = ZonedDateTime.now( zonedId );
		ZonedDateTime endDate   = startDate;
		
		// Esta 2 lineas es para saber el directorio raiz donde está corriendo la aplicacion 
		final File file = new File(".");
    System.out.println("file = " + file.getAbsoluteFile().getParent());
		
		// Chequear parámetros de entrada (fecha)
		if (args.length >= 2) {
			// Fecha DESDE y fecha HASTA
			String fecha   = args[0];
			LocalDate date = LocalDate.parse(fecha, dateFormatter);
			startDate      = date.atStartOfDay(zonedId);
			String fechaEnd   = args[1];
			LocalDate dateEnd = LocalDate.parse(fechaEnd, dateFormatter);
			endDate           = dateEnd.atStartOfDay(zonedId);
		} else if (args.length >= 1) {
			// Fecha DESDE
			String fecha   = args[0];
			LocalDate date = LocalDate.parse(fecha, dateFormatter);
			startDate      = date.atStartOfDay(zonedId);
			endDate        = startDate;
		}
		
		try {
			// Obtener las cotizaciones a grabar en el MySql
			currencies = loadJsonFromFileToArray("settings/HotGo_cotizaciones.json");

			// Iterar sobre las fechas DESDE y HASTA y ejecutar el proceso
			Period period = Period.parse("P1D");
			while (startDate.isBefore(endDate.plus(period))) {
			    System.out.println("*** DAY: " + startDate);
				
				try {
					// Grabar las cotizaciones en el MySql
					outMsgCurrencies = saveExchangeRates(startDate.format(dateFormatter), currencies);
		           	
				} catch (RuntimeException e) {
					outMsgErrors = e.getMessage();
					
				} finally {
					
					// Open the LOG file to output the result messages: outMsgCurrencies  &  outMsgErrors
					String logFileName = "hotgo_cotizaciones_diarias_" + startDate.format(DateTimeFormatter.ofPattern("yyyy_MM")) + ".log";
					FileWriter fw = new FileWriter("static/" + logFileName, true);  // linux
					// DEBUG
					//FileWriter fw = new FileWriter("C:\\var\\local\\" + logFileName, true);  // win
					BufferedWriter bw = new BufferedWriter(fw);
					// Write the Process Date
					outMessage = "\r\n\r\n----------------------------------------------------------------------------------------------------\r\n"
							   + startDate + "\r\n"
							   + "*** COTIZACIONES A GRABAR:\r\n"
							   + currencies.toString();  //.getAsString(); 
					// Imprimir Resultado
					if (outMsgCurrencies != "") {
						outMessage += "\r\n\r\n*** RESULTADO:\r\n"
							    + outMsgCurrencies;
					}
					// Imprimir Errores
					if (outMsgErrors != "") {
						outMessage += "\r\n\r\n*** HAN OCURRIDO ERRORES:\r\n"
								    + outMsgErrors;
						// Write errors to LOG file
					};
					bw.write(outMessage);
				    bw.close();
					
					// Send MAIL to advice of the errors
					if (outMsgErrors != "" || huboErrores) {
						System.out.println("ERRORES: " + outMsgErrors);
						SendEmail("itcorp@claxson.com,gonzalo.mtanoira@gmail.com", "itcorp@claxson.com", "HotGo: ERROR al grabar las cotizaciones diarias al MySql", outMessage);
					};
		
				}
				
			    startDate = startDate.plus(period);
			}	
			
		} catch (Exception e) {
			System.out.println("ERRORES: " + e.getMessage());
			SendEmail("itcorp@claxson.com,gonzalo.mtanoira@gmail.com", "itcorp@claxson.com", "HotGo: ERROR al grabar las cotizaciones diarias al MySql", e.getMessage());
		}

	}

	/** ***********************************************************************************************************************
	 * Method: loadJsonFromFileToArray()
	 * Lee desde un archivo un objeto del tipo JsonArray
	 * 
	 * @param  fileName: path/nombre del archivo json a leer
	 * 
	 * @return JsonArray
	 * 
	 * @throws RuntimeException 
	 */
	private static JsonArray loadJsonFromFileToArray(String fileName) throws  RuntimeException {
		
		try {

		    // Parse to a JSON object
			JsonParser parser = new JsonParser();
	        JsonArray rtnArray = parser.parse(new FileReader(fileName)).getAsJsonArray();
	        
	        return rtnArray;
			
		} catch (JsonParseException e) {
			throw new RuntimeException("error parsing the json file at loadJsonFromFileToArray() method / " + e.getMessage());
	        
		} catch (IOException e) {
			throw new RuntimeException("file not found in loadJsonFromFileToArray(): " + fileName);
	        
		} catch (Exception e) {
			throw new RuntimeException("Other exception in loadJsonFromFileToArray(): " + e.getMessage());
		}
	}

	/** ***********************************************************************************************************************
	 * Method: loadJsonFromFileToObject()
	 * Lee desde un archivo un objeto del tipo JsonObject
	 * 
	 * @param  fileName: path/nombre del archivo json a leer
	 *  
	 * @return JsonObject
	 * 
	 * @throws RuntimeException
	 */
	private static JsonObject loadJsonFromFileToObject(String fileName) throws  RuntimeException {
		
		try {

		    // Parse to a JSON object
			JsonParser parser = new JsonParser();
	        JsonObject object = parser.parse(new FileReader(fileName)).getAsJsonObject();
	        
	        return object.getAsJsonObject();
			
		} catch (JsonParseException e) {
			throw new RuntimeException("error parsing the json file at loadJsonFromFileToObject() method");
	        
		} catch (IOException e) {
			throw new RuntimeException("file not found in loadJsonFromFileToObject(): " + fileName);
		}
	}

	/** ***********************************************************************************************************************
	 * Method: saveExchangeRates()
	 * Grabar en un MySql todas las cotizaciones solicitadas en 
	 * 
	 * @param  fecha: fecha de las cotizaciones
	 * @param  exchangeRatesToSave: array con las cotizaciones a grabar en el MySql
	 * 
	 * @return String
	 * 
	 * @throws RuntimeException 
	 */
	private static String saveExchangeRates(String fecha, JsonArray exchangeRatesToSave) throws RuntimeException {
		
		JsonArray outMessage = new JsonArray();
		
		// Conectar con la base de datos MySql
		try {

			// Iterar sobre las cotizaciones, calcularlas y guardarlas en el MySql
			for (int i=0; i<exchangeRatesToSave.size()  ; i++) {
				
				// Buscar la cotizacion en SAP
				String monedaOrigen  = exchangeRatesToSave.get(i).getAsJsonObject().get("origen").getAsString(); 
				String monedaDestino = exchangeRatesToSave.get(i).getAsJsonObject().get("destino").getAsString(); 
				String rateType      = exchangeRatesToSave.get(i).getAsJsonObject().get("rateType").getAsString(); 
				String calcular      = exchangeRatesToSave.get(i).getAsJsonObject().get("calcular").getAsString(); 
				JsonObject cotizacion = getCotizacion(fecha, monedaOrigen, monedaDestino, rateType);
					
				// Actualizar la cotización del dia de la fecha
				String updMessage = updateCotizacionFecha(fecha, monedaOrigen, monedaDestino, calcular, cotizacion);
				outMessage.add(updMessage);
				
				// Insertar (INSERT INTO) la cotización del dia de mañana (fecha + 1)
			  String insMessage = insertCotizacionFecha(fecha, monedaOrigen, monedaDestino, calcular, cotizacion);
			  outMessage.add(insMessage);
			}
			
		} catch (RuntimeException e) {
			throw new RuntimeException(e.getMessage());
		}
		
		return outMessage.toString();
	}

	/** ***********************************************************************************************************************
	 * Method: updateCotizacionFecha()
	 * Actualiza la cotización obtenida en el MySql para el día FECHA 
	 * 
	 * @param  fecha: fecha de las cotizacion
	 * @param  monedaOrigen: ID SAP de la moneda de origen
	 * @param  monedaDestino: ID SAP de la moneda de destino
	 * @param  calcular: indica como guardar al cotizacion solicitada:
	 * 				   = "normal": se guarda tal cual se recibe de moneda ORIGEN a moneda DESTINO.
	 *                 = "invertir": se guarda invirtiendo las monedas y sus cotizaciones, de moneda DESTINO a moneda ORIGEN
	 * @param  cotizacion: JSON con los datos de la cotización obtenida por el SAP via la api /api/exchangerate?...
	 * 
	 * @return String: mensaje de retorno con el resultado de la operacion de UPDATE
	 */
	private static String updateCotizacionFecha(
			String fecha, 
			String monedaOrigen,
			String monedaDestino,
			String calcular,
			JsonObject cotizacion) throws RuntimeException {
		
		Connection dbConnMySql = null;
		PreparedStatement dbSqlCmd = null;
		String logMessage = "";
		String sqlUpdateRecord = "UPDATE cotizaciones_diarias "
	             + "SET cot_directa = ?, cot_indirecta = ? "
	             + "WHERE fecha = ? AND moneda_origen = ? AND moneda_destino = ?";
		
		// Conectar con la base de datos MySql
		try {
			// Escribir el LOG
			if (calcular.equals("invertir")) {
				// INVERTIR: guarda la cotización Al revés de como fue calculada
				logMessage += "Fecha " + fecha + ", de " + monedaDestino + " a " + monedaOrigen;
			} else {
				// NORMAL: guarda la cotización como fue calculada
				logMessage += "Fecha " + fecha + ", de " + monedaOrigen + " a " + monedaDestino;
			}
			
			// Grabar la cotizacion en el MySql
			if (!cotizacion.isJsonNull()) {
				BigDecimal cotDirecta   = cotizacion.get("directExchange").getAsBigDecimal();
				BigDecimal cotIndirecta = cotizacion.get("inDirectExchange").getAsBigDecimal();
				
				// Conectar con el MySql
				dbConnMySql = getMySqlConnection();

				if (calcular.equals("invertir")) {
					// INVERTIR: guarda la cotización Al revés de como fue calculada
					dbSqlCmd = dbConnMySql.prepareStatement(sqlUpdateRecord);
					dbSqlCmd.setBigDecimal(1, cotIndirecta);
					dbSqlCmd.setBigDecimal(2, cotDirecta);
					dbSqlCmd.setString(3, fecha);
					dbSqlCmd.setString(4, monedaDestino);
					dbSqlCmd.setString(5, monedaOrigen);
					dbSqlCmd.executeUpdate();
					
				} else {
					// NORMAL: guarda la cotización como fue calculada
					dbSqlCmd = dbConnMySql.prepareStatement(sqlUpdateRecord);
					dbSqlCmd.setBigDecimal(1, cotDirecta);
					dbSqlCmd.setBigDecimal(2, cotIndirecta);
					dbSqlCmd.setString(3, fecha);
					dbSqlCmd.setString(4, monedaOrigen);
					dbSqlCmd.setString(5, monedaDestino);
					dbSqlCmd.executeUpdate();
				}

				// Escribir el resultado en el LOG
				if (calcular.equals("invertir")) {
					// INVERTIR: guarda la cotización Al revés de como fue calculada
					logMessage += ", (D): " + cotIndirecta.toString() + " / (I): " + cotDirecta.toString();
				} else {
					// NORMAL: guarda la cotización como fue calculada
					logMessage += ", (D): " + cotDirecta.toString() + " / (I): " + cotIndirecta.toString();
				}
				
			} else {
				logMessage += ", sin cotización SAP desde la api /api/exchangerate.";
				huboErrores = true;
			}
			
		} catch (SQLException e) {
			logMessage += ", SQL UPDATE error: " + e.getMessage();
			huboErrores = true;
			
		} catch (Exception e) {
			logMessage += ", (SQL UPDATE) Exception error: " + e.getMessage();
			huboErrores = true;
		
		} finally {
			try {
				if (dbSqlCmd != null) {
					dbSqlCmd.close();
				}
				if (dbConnMySql != null) {
					dbConnMySql.close();
				}
			} catch (SQLException e) {
				logMessage += e.getMessage();
			}
		}
		
		return logMessage;
	}

	/** ***********************************************************************************************************************
	 * Method: insertCotizacionFecha()
	 * Inserta la cotización obtenida en el MySql para el día FECHA + 1
	 * 
	 * @param  fecha: fecha de las cotizacion (a esta fecha hay que sumarle 1 dia)
	 * @param  monedaOrigen: ID SAP de la moneda de origen
	 * @param  monedaDestino: ID SAP de la moneda de destino
	 * @param  calcular: indica como guardar al cotizacion solicitada:
	 * 				   = "normal": se guarda tal cual se recibe de moneda ORIGEN a moneda DESTINO.
	 *                 = "invertir": se guarda invirtiendo las monedas y sus cotizaciones, de moneda DESTINO a moneda ORIGEN
	 * @param  cotizacion: JSON con los datos de la cotización obtenida por el SAP via la api /api/exchangerate?...
	 * 
	 * @return String: mensaje de retorno con el resultado de la operacion de UPDATE
	 */
	private static String insertCotizacionFecha(
		String fecha, 
		String monedaOrigen,
		String monedaDestino,
		String calcular,
		JsonObject cotizacion
	) throws RuntimeException {
		
		Connection dbConnMySql = null;
		PreparedStatement dbSqlCmd = null;
		String logMessage = "";
		String sqlInsertInto = "INSERT INTO cotizaciones_diarias "
	             + "(fecha, moneda_origen, moneda_destino, cot_directa, cot_indirecta) VALUES (?, ?, ?, ?, ?)";
		
		// Calcular la fecha de mañana
		DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
		ZoneId            zonedId       = ZoneId.of( "America/Argentina/Buenos_Aires" );
		LocalDate         date_1        = LocalDate.parse(fecha, dateFormatter);  // convertir parametro fecha en un campo FECHA
		ZonedDateTime     date_2        = date_1.atStartOfDay(zonedId).plus(Period.parse("P1D"));  // sumar 1 dia
		String            fechaManana   = date_2.format(dateFormatter);  // Convertir la fehca a YYYMMDD
		
		// Conectar con la base de datos MySql y guardar la cotizacion
		try {
			// Escribir el LOG
			if (calcular.equals("invertir")) {
				// INVERTIR: guarda la cotización Al revés de como fue calculada
				logMessage += "Fecha " + (fechaManana) + ", de " + monedaDestino + " a " + monedaOrigen;
			} else {
				// NORMAL: guarda la cotización como fue calculada
				logMessage += "Fecha " + (fechaManana) + ", de " + monedaOrigen + " a " + monedaDestino;
			}
			
			// Grabar la cotizacion en el MySql
			if (!cotizacion.isJsonNull()) {
				BigDecimal cotDirecta   = cotizacion.get("directExchange").getAsBigDecimal();
				BigDecimal cotIndirecta = cotizacion.get("inDirectExchange").getAsBigDecimal();
				
				// Conectar con el MySql
				dbConnMySql = getMySqlConnection();

				if (calcular.equals("invertir")) {
					// INVERTIR: guarda la cotización Al revés de como fue calculada
					dbSqlCmd = dbConnMySql.prepareStatement(sqlInsertInto);
					dbSqlCmd.setString(1, fechaManana);
					dbSqlCmd.setString(2, monedaDestino);
					dbSqlCmd.setString(3, monedaOrigen);
					dbSqlCmd.setBigDecimal(4, cotIndirecta);
					dbSqlCmd.setBigDecimal(5, cotDirecta);
					dbSqlCmd.executeUpdate();
					
				} else {
					// NORMAL: guarda la cotización como fue calculada
					dbSqlCmd = dbConnMySql.prepareStatement(sqlInsertInto);
					dbSqlCmd.setString(1, fechaManana);
					dbSqlCmd.setString(2, monedaOrigen);
					dbSqlCmd.setString(3, monedaDestino);
					dbSqlCmd.setBigDecimal(4, cotDirecta);
					dbSqlCmd.setBigDecimal(5, cotIndirecta);
					dbSqlCmd.executeUpdate();
				}

				// Escribir el resultado en el LOG
				if (calcular.equals("invertir")) {
					// INVERTIR: guarda la cotización Al revés de como fue calculada
					logMessage += ", (D): " + cotIndirecta.toString() + " / (I): " + cotDirecta.toString();
				} else {
					// NORMAL: guarda la cotización como fue calculada
					logMessage += ", (D): " + cotDirecta.toString() + " / (I): " + cotIndirecta.toString();
				}
				
			} else {
				logMessage += ", sin cotización SAP desde la api /api/exchangerate.";
				huboErrores = true;
			}
			
		} catch (SQLException e) {
			logMessage += ", SQL INSERT error: " + e.getMessage();
			huboErrores = true;
			
		} catch (Exception e) {
			logMessage += ", (SQL INSERT) Exception error: " + e.getMessage();
			huboErrores = true;
		
		} finally {
			try {
				if (dbSqlCmd != null) {
					dbSqlCmd.close();
				}
				if (dbConnMySql != null) {
					dbConnMySql.close();
				}
			} catch (SQLException e) {
				logMessage += e.getMessage();
			}
		}
		return logMessage;
	}

	/** ***************************************************************************************************************
	 *  Method: getCotizacion()
	 *  Buscar una cotizacion en el SAP
	 *  
	 *  @param fecha (String): fecha de la cotizacion a buscar
	 *  @param monedaOrigen (String): id SAP de la moneda origen 
	 *  @param monedaDestino (String): id SAP de la moneda destino
	 *  
	 *  @return JsonObject: cotización solicitada. Se devuelve un JsonObject NULL si no se pudo obtener una cotizacion
	 *   
	*/
	private static JsonObject getCotizacion(String fecha, String monedaOrigen, String monedaDestino, String rateType) throws RuntimeException {
		
    HttpURLConnection connection = null;
		JsonObject cotizacion = new JsonObject();
		String serverUrl = "";
		
		try {

			// Obtener el URL del API server 
			JsonObject hotgoInfo = loadJsonFromFileToObject("settings/HotGo_settings.json");  // linux
			//JsonObject serverInfo = loadJsonFromFileToObject("C:\\var\\local\\HotGo_settings.json");   // win
			serverUrl = hotgoInfo.get("SapGwServerUrl").getAsString();
			
			// Buscar la cotizacion en el SAP
			try {
			  
			    // Create connection
				String queryParams = "ratetype=" + rateType
				  + "&monorigen=" + monedaOrigen
					+ "&mondestino=" + monedaDestino
					+ "&fecha=" + fecha;
			  URL url = new URL(serverUrl + "/api/exchangerate?" + queryParams);
			  connection = (HttpURLConnection) url.openConnection();
			  connection.setRequestMethod("GET");
			    
				connection.setUseCaches(false);
			  connection.setDoOutput(true);
				
			    // Load the response
				InputStreamReader  in = new InputStreamReader(connection.getInputStream());
			  BufferedReader     rd = new BufferedReader(in);
			  StringBuffer response = new StringBuffer();
			  String line;
			  while ((line = rd.readLine()) != null) {
			  	response.append(line);
			   	response.append('\r');
			  }
			  rd.close();

				// Response OK?
			  int statusCode = connection.getResponseCode();
			  if (statusCode == 200) { 
				    
				  // Parse to a JSON object
				  String responseData = response.toString();
				  JsonParser parser = new JsonParser();
			    cotizacion = parser.parse(responseData).getAsJsonObject();
			    
			  } else {
				  throw new RuntimeException("API-0011(E): cannot get data from API " + serverUrl);
			  }
			    
			} catch (JsonParseException e) {
			  throw new RuntimeException("JSON parser error in getCotizacion(): " + e.toString());

			} catch (IOException e) {
				System.out.println(e.getMessage());  
				System.out.println(e.getStackTrace());
				e.printStackTrace();
				throw new RuntimeException("HTTP error calling SAPGateway Server in getCotizacionI(): " + e.getMessage().toString());
			    
			} catch (Exception e) {
	      throw new RuntimeException("in getCotizacion(): " + e.toString());

			} finally {
		    if (connection != null) {
			    	connection.disconnect();
		    }
			}
			
		} catch (RuntimeException e) {
			throw new RuntimeException("API-0024(E): no se pudo obtener las credenciales para acceder al server SAPGateway (" + e.getMessage() +")");
		}
		
		return cotizacion;
	}

	/** ***************************************************************************************************************
	 *  Method: getMySqlConnection()
	 *  Establece la conexión con la base de datos MySql
	*/
	private static Connection getMySqlConnection() throws RuntimeException {
		
		Connection conn;
		String serverName = "";
		String serverPort = "";
		String userName   = "";
		String password   = "";
		
		// Obtener el nombre y el puerto del server PortalAdmin, donde se encuentra el MySql
		try {
			JsonObject hotgoInfo = loadJsonFromFileToObject("settings/HotGo_settings.json");  
			JsonObject serverInfo = hotgoInfo.get("MySqlAws").getAsJsonObject();
			
			serverName = serverInfo.get("host").getAsString();
			serverPort = serverInfo.get("port").getAsString();
			userName   = serverInfo.get("userName").getAsString();
			password   = serverInfo.get("password").getAsString();
			
		} catch (RuntimeException e) {
			throw new RuntimeException("API-0024(E): no se pudo obtener las credenciales para acceder al MySql de AWS (" + e.getMessage() +")");
		}
		
		// Establecer la conexión
		try {
			String driver = "com.mysql.cj.jdbc.Driver";
			String url    = "jdbc:mysql://" + serverName +":"+ serverPort + "/HGDW?useLegacyDatetimeCode=false&serverTimezone=America/Argentina/Buenos_Aires";
			Class.forName(driver);
			
			conn = DriverManager.getConnection(url, userName, password);
		} catch (Exception e) {
			throw new RuntimeException("API-0025(E): no se pudo establecer una conexión con el MySql de AWS (" + serverName + "): " + e.getMessage());
		}
		
		return conn;
	}

	private static void SendEmail (String to_recipients, String from_recipient, String subject, String body) throws RuntimeException {

		// Assuming you are sending email from localhost
		String host = "10.4.0.61";

		// Get system properties
		Properties properties = System.getProperties();

		// Setup mail server
		properties.setProperty("mail.smtp.host", host);

		// Get the default Session object.
		Session session = Session.getDefaultInstance(properties);

		try {
			// Create a default MimeMessage object.
			MimeMessage message = new MimeMessage(session);

			// Set From: header field of the header.
			message.setFrom(new InternetAddress(from_recipient));

			// Set To: header field of the header.
			InternetAddress[] addr = javax.mail.internet.InternetAddress.parse(to_recipients);
			message.addRecipients(Message.RecipientType.TO, addr);   

			// Set Subject: header field
			message.setSubject(subject);

			// Now set the actual message
			message.setText(body);

			// Send message
			Transport.send(message);
			// System.out.println("Sent message successfully....");
         
		}catch (MessagingException e) {
			System.out.println(e.getMessage());
			throw new RuntimeException("API-0006(E): error sending mail: " + e.getMessage());
		}
      
	}
      
}
