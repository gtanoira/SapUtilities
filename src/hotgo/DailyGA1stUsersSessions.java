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
   Este programa graba diariamente las 1ras. sesiones de cada usuario de la plataforma HotGo.tv en un MySql en AWS.
   Los datos son obtenidos de Google Analytics.
   Tabla en el MySql: HGDW.ga_1st_users_sessions
   
   Este programa se ejecuta via el crontab de linux, una vez al día.
   
   @param startDate (String, formato: yyyymmdd): si este parámetro es enviado al ejecutar este programa, se grabarán las cotizaciones 
                especificada por este parámetro. De ser null o undefined, se grabarán las del día anterior a la fecha.
   @param endDate (String, formato: yyyymmdd): si este parámetro es enviado al ejecutar este programa, se grabarán las cotizaciones 
                especificada por este parámetro. De ser null o undefined, se utilizará startDate.
                
   El programa escribe un LOG en el archivo static/ga_1st_users_sessions_YYYY_MM.log con los resultados de la operaciones
 
*/
public class DailyGA1stUsersSessions {
	
	public static boolean huboErrores = false;
	
	public static void main(String[] args) throws RuntimeException, IOException {
		
		// Initialize variables
		String outMessage = "";
		String outMsgErrors = "";
	  String outMsgCurrencies = "";
	
		// Establecer el dia de cálculo de las cotizaciones
		DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
		ZoneId        zonedId   = ZoneId.of( "America/Argentina/Buenos_Aires" );
		ZonedDateTime startDate = ZonedDateTime.now( zonedId ).minusDays(1);
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

			// Iterar sobre las fechas DESDE y HASTA y ejecutar el proceso
			Period period = Period.parse("P1D");
			while (startDate.isBefore(endDate.plus(period))) {
			    System.out.println("*** DAY: " + startDate);
				
				try {
					// Grabar las cotizaciones en el MySql
					outMsgCurrencies = callGA1stUsersSessions(startDate.format(dateFormatter));
		           	
				} catch (RuntimeException e) {
					outMsgErrors = e.getMessage();
					
				} finally {
					
					// Open the LOG file to output the result messages: outMsgCurrencies  &  outMsgErrors
					String logFileName = "ga_1st_users_sessions_" + startDate.format(DateTimeFormatter.ofPattern("yyyy_MM")) + ".log";
					FileWriter fw = new FileWriter("static/" + logFileName, true);
					// DEBUG
					//FileWriter fw = new FileWriter("C:\\var\\local\\" + logFileName, true);  // win
					BufferedWriter bw = new BufferedWriter(fw);
					
					// Write the Process output
					outMessage = "\r\n" + startDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " - " + outMsgCurrencies;
					// Agregar errores
					if (outMsgErrors != "") {
						outMessage += outMsgErrors;
					};
					bw.write(outMessage);
				  bw.close();
					
					// Send MAIL to advice of the errors
					if (outMsgErrors != "" || huboErrores) {
						System.out.println("ERRORES: " + outMsgErrors);
						SendEmail("itcorp@claxson.com,gonzalo.mtanoira@gmail.com", "itcorp@claxson.com", "HotGo: ERROR al ejecutar el proceso ga_1st_users_sessions", outMessage);
					};
		
				}
			  startDate = startDate.plus(period);
			}	
			
		} catch (Exception e) {
			System.out.println("ERRORES: " + e.getMessage());
			SendEmail("itcorp@claxson.com,gonzalo.mtanoira@gmail.com", "itcorp@claxson.com", "HotGo: ERROR en el proceso DailyGA1stUsersSessions.java", e.getMessage());
		}

	}

	/** ***********************************************************************************************************************
	 * callGA1stUsersSessions()
	 * 
	 * Llama a la api {HGDW_backend_server}/api2/ga/1st_user_session?fechadesde=...&fechahasta=...
	 * 
	 * @param  fechadesde: yyyymmdd
	 * 
	 * @return String
	 * 
	 * @throws RuntimeException 
	 */
	private static String callGA1stUsersSessions(String fecha) throws RuntimeException {
		
    HttpURLConnection connection = null;
		JsonObject rtnMessage = new JsonObject();
		String serverUrl = "";
		
		try {

			// Obtener el URL del API server 
			JsonObject hotgoInfo = loadJsonFromFileToObject("settings/clx_gateway_settings.json");  // linux
			//JsonObject serverInfo = loadJsonFromFileToObject("C:\\var\\local\\clx_gateway_settings.json");   // win
			serverUrl = hotgoInfo.get("UrlHotgoBackend").getAsString();
			
			try {
			  
			  // Create connection
				String queryParams = "fechadesde=" + fecha.substring(0, 4) +"-"+ fecha.substring(4, 6) +"-"+ fecha.substring(6, 8) 
				  + "&fechahasta=" + fecha.substring(0, 4) +"-"+ fecha.substring(4, 6) +"-"+ fecha.substring(6, 8);
			  URL url = new URL(serverUrl + "/api2/ga/1st_user_session?" + queryParams);
			  connection = (HttpURLConnection) url.openConnection();
			  connection.setRequestMethod("GET");
			  connection.setRequestProperty("x-token-hgdw", "BYPASS");
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
			  // Parse to a JSON object
        String responseData = response.toString();
        JsonParser parser = new JsonParser();
        rtnMessage = parser.parse(responseData).getAsJsonObject();
        // Check status code
        if (statusCode != 200) { 
			    if (connection != null) { connection.disconnect(); }
				  throw new RuntimeException("API-0011(E): cannot get data from API " + serverUrl + " - " + rtnMessage.get("message").getAsString());
			  }
			    
			} catch (JsonParseException e) {
			  throw new RuntimeException("JSON parser error in callGA1stUsersSessions(): " + e.toString());

			} catch (IOException e) {
				System.out.println(e.getMessage());  
				System.out.println(e.getStackTrace());
				e.printStackTrace();
				throw new RuntimeException("HTTP error calling backend server in callGA1stUsersSessions(): " + e.getMessage().toString());
			    
			} catch (Exception e) {
	      throw new RuntimeException("in callGA1stUsersSessions(): " + e.toString());

			} finally {
		    if (connection != null) {
			    	connection.disconnect();
		    }
			}
			
		} catch (RuntimeException e) {
			throw new RuntimeException("API-0024(E): no se pudo obtener las credenciales para acceder al server HotGo backend server (" + e.getMessage() +")");
		}
		
		return rtnMessage.get("message").getAsString();
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
