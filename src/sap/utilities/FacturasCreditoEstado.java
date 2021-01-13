package sap.utilities;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.*;
import javax.mail.internet.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoStructure;
import com.sap.conn.jco.JCoTable;

/**
   Este programa graba diariamente el estado de las facturas de crédito en el AFIP en una tabla en el MySql.
   
   Este programa se ejecuta via el crontab de linux, una vez al día.
   
   El programa lee desde el SAP a través de una rfc ZSD_RFC_FECRED las facturas de crédito emitidas al AFIP y las envía 
   a una base de datos MySql para que continúe el proceso. 
     
*/
public class FacturasCreditoEstado {
	
	public static boolean huboErrores = false;
	
	public static void main(String[] args) throws RuntimeException, IOException {
		
		// Initialize variables
		JsonArray dataToExtract = new JsonArray();
		String outMessage = "";  // LOG del proceso
		boolean sendMail = false;
		
		try {
			// Obtener los datos de las empresas (org.de Vtas.) a procesar
			dataToExtract = loadJsonFromFileToArray("settings/facturas_credito_estado.json");
			// DEBUG
			//dataToExtract = loadJsonFromFileToArray("C:\\var\\local\\cotizaciones_diarias.json");  // win
			
		  // Establecer la fecha de proceso
      DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
      ZoneId        zonedId   = ZoneId.of( "America/Argentina/Buenos_Aires" );
      ZonedDateTime processDate = ZonedDateTime.now( zonedId );

			// Procesar las empresas
			for (JsonElement orgVtas : dataToExtract) {
			    System.out.println("*** Org.vtas: " + orgVtas);
			    
			    // Obtener la empresa a procesar
			    String empresa = orgVtas.getAsJsonObject().get("orgVtas").getAsString();
			    System.out.println("*** Empresa: " + empresa);
          
			    
			  // Procesar las sucursales
			  try {

			    JsonArray sucursales = orgVtas.getAsJsonObject().get("sucursales").getAsJsonArray();
				  for (JsonElement datoSucursal : sucursales) {
				    
				    JsonArray dataToMysql = new JsonArray();
				    String sucursal = datoSucursal.getAsString();
				    System.out.println("*** Sucursal: " + sucursal);
				    
				    final File file = new File(".");
				    System.out.println("*** Main PATH: " + file.getAbsoluteFile().getParent());
            
				    // Buscar al SAP las facturas de credito
				    try {
				      
				      // Inicializar SAP connection 
			        // for DEBUG
			        //System.setProperty("jco.destinations.dir", "c:\\");   // for WINDOWS
			        JCoDestination sapDestination = JCoDestinationManager.getDestination("settings/SAPCONNECT");
			        JCoFunction sapBapi = setBapiFunction(sapDestination, "ZSD_RFC_FECRED");
			        
			        // Initialize BAPI IMPORT DATA
			        setBapiImportData(sapBapi, empresa, sucursal);
			        
			        // Ejecutar la bapi
			        sapBapi.execute(sapDestination);
			        
			        // Obtener el resultado
			        dataToMysql = getBapiExportData(sapBapi);
			        System.out.println("*** Array: ");
			        System.out.println(dataToMysql.toString());

				      
				    } catch (RuntimeException e) {
				      outMessage += e.getMessage().toString();
				      sendMail = true;
				    }
				    
				    // Guardarlas en el MySql
				    try {
				      outMessage += "\r\nEmpresa: " + empresa + " - Sucursal: " + sucursal;
				      
				      // Verificar que tenga por lo menos 1 factura
				      if (dataToMysql.size() != 0) {
				        outMessage += "\r\n" + insertDataToMySql(dataToMysql, empresa, sucursal, processDate);
				      } else {
				        outMessage += "\r\nNo hay comprobantes que procesar.";
				      }
 				    } catch (RuntimeException e) {
              outMessage += e.getMessage().toString();
              sendMail = true;
            }
	          				    
				  }  // for loop sucursales
				  
				} catch (RuntimeException e) {
				  outMessage += e.getMessage();
					
				} finally {
				  
					// Open the LOG file to output the result messages: outMsgCurrencies  &  outMsgErrors
					String logFileName = "facturas_credito_estado_" + processDate.format(DateTimeFormatter.ofPattern("yyyy_MM")) + ".log";
					FileWriter fw = new FileWriter("static/" + logFileName, true);  // linux
					// DEBUG
					//FileWriter fw = new FileWriter("C:\\var\\local\\" + logFileName, true);  // win
					BufferedWriter bw = new BufferedWriter(fw);

          // Esta 2 lineas es para saber el directorio raiz donde está corriendo la aplicacion 
          final File file = new File(".");
          bw.write("file = " + file.getAbsoluteFile().getParent());

          // Guardar la fecha de proceso
					outMessage = "\r\n\r\n----------------------------------------------------------------------------------------------------\r\n"
					  + processDate + "\r\n"
					  + outMessage;

					// Guardar el Resultado
					bw.write(outMessage);
				  bw.close();
				  
				  System.out.println("ERRORES: " + outMessage);
				  
		
				}
			}  // for loop  orgVtas	
			
		} catch (Exception e) {
			System.out.println("ERRORES: " + e.getMessage());
			SendEmail("itcorp@claxson.com,gonzalo.mtanoira@gmail.com", "itcorp@claxson.com", "ERROR al grabar las facturas de credito estado al MySql", e.getMessage());
		
		} finally {
		  if (sendMail) {
		    SendEmail("itcorp@claxson.com,gonzalo.mtanoira@gmail.com", 
		      "itcorp@claxson.com",
		      "ERROR en el informe de facturas de crédito",
		      outMessage);
		  }
		}

	}

  /** ***************************************************************************************************************
   *  This method check and initialize the BAPI
  */
  private static JCoFunction setBapiFunction(JCoDestination sapDestination, String bapiName) throws RuntimeException, JCoException {
    
    JCoFunction fxnBapi = sapDestination.getRepository().getFunction(bapiName);
    
    if(fxnBapi == null) {
      throw new RuntimeException("API-0007(E): the bapi " + bapiName + " was not found in SAP");
    };
    
    return fxnBapi;
  
  }

  /** ***************************************************************************************************************
   *  This method SETs the IMPORT DATA for the BAPI 
  */
  private static void setBapiImportData(JCoFunction fxnBapi, String orgVtas, String sucursal) throws RuntimeException {
    
    try {
    
      // Send the IMPORT data to the structure I_CABECERA
      fxnBapi.getImportParameterList().setValue("I_BUKRS", orgVtas);
      fxnBapi.getImportParameterList().setValue("I_BRNCH", sucursal);

    } catch (Exception e) {
      throw new RuntimeException("Error completando el SAP IMPORT fields en setBapiImportData: " + e.getMessage().toString());
    }
  }

  /** ***************************************************************************************************************
   *  This method Gets the data from the EXPORT tables from the Bapi 
   *  and convert the SAP data into a JSON structure 
  */
  private static JsonArray getBapiExportData(JCoFunction fxnBapi) throws RuntimeException {
      
    JsonArray exportData = new JsonArray();
    
    try {
      // Obtener los datos
      JCoTable dataFromBapi = fxnBapi.getTableParameterList().getTable("T_SALIDA");
      // Convertir los datos
      exportData = convertDataToJSON(dataFromBapi); 
          
    } catch (Exception e) {
      throw new RuntimeException("ERROR en GetBapiExportData(): " + e.getMessage().toString());
    }

    return exportData;
  };

  /** ***********************************************************************************************************************
   * Method: insertDataToMySql()
   * Inserta los datos obtenidos del SAP en una base de datos MySql de CSI
   * 
   * @param  JsonArray dataToUpload: datos a grabar en la bdatos
   * @param  String empresa: ID SAP de la empresa
   * @param  String sucursal: ID SAP de la sucursal dentro de la empresa
   * @param  ZonedDateTime processDate: fecha que se ejecuta este proceso
   * 
   * @return String: mensaje de retorno con el resultado de la operacion
   */
  private static String insertDataToMySql(
    JsonArray dataToUpload, 
    String empresa,
    String sucursal,
    ZonedDateTime processDate) throws RuntimeException {
    
    Connection dbConnMySql = null;
    PreparedStatement dbSqlCmd = null;
    String logMessage = "";
    Pattern regExpFacturaNo = Pattern.compile("([0-9]+)[A-Z]{1}([0-9]+)");
    String sqlCallSP = "CALL Agrega_Documento(?, ?, ?, ?, ?, ?, ?, ?, ?)";
    
    // Calcular la fecha de proceso
    DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HHmm");
    String fechaProceso = processDate.format(dateFormatter);
    String horaProceso = processDate.format(timeFormatter);
    
    try {
  
      // Conectar con el MySql
      dbConnMySql = getMySqlConnection();
      
      // Iterar el array dataToUpload y guardar los datos
      for (JsonElement factura : dataToUpload) {
        
        logMessage += "\r\nFactura: " + factura.getAsJsonObject().get("facturaNo").getAsString();
        
        try {
          
          // Variables
          int facturaNo = 0;
          int ptoVta = 0;
          
          // Evaluar el no. de factura a través de un regExp para extraer su No. y su Pto.Vta.
          Matcher m = regExpFacturaNo.matcher(factura.getAsJsonObject().get("facturaNo").getAsString());
          if (m.find()) {
            facturaNo = Integer.parseInt(m.group(2));
            ptoVta = Integer.parseInt(m.group(1));
            
            // String empresa = orgVtas.getAsJsonObject().get("orgVtas").getAsString();
            dbSqlCmd = dbConnMySql.prepareStatement(sqlCallSP);
            dbSqlCmd.setString(1, factura.getAsJsonObject().get("cuit").getAsString());
            dbSqlCmd.setString(2, factura.getAsJsonObject().get("tipoCbte").getAsString());
            dbSqlCmd.setString(3, factura.getAsJsonObject().get("facturaLetra").getAsString());
            dbSqlCmd.setInt(4, facturaNo);
            dbSqlCmd.setInt(5, ptoVta);
            dbSqlCmd.setString(6, fechaProceso);
            dbSqlCmd.setString(7, horaProceso);
            dbSqlCmd.setString(8, factura.getAsJsonObject().get("clienteName").getAsString());
            dbSqlCmd.setString(9, factura.getAsJsonObject().get("refSd").getAsString());
            dbSqlCmd.executeUpdate();
            
            logMessage += " - Ok";
            
          } else {
            logMessage += " - ERROR: no se pudo obtener el punto de venta o el no. de factura.";
          }
         
        } catch (SQLException e) {
          logMessage += " - " + e.getMessage();
        } 
      }  // for loop dataToUpload
      
    } catch (Exception e) {
      logMessage += "\r\nSQL error: " + e.getMessage();
    
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
   * Metodo: convertDataToJSON
   * convierte los datos a formato JSON 
   */
  private static JsonArray convertDataToJSON (JCoTable dataToConvert) throws RuntimeException {
  
    JsonArray exportData = new JsonArray();
    JsonObject itemJson = new JsonObject();
  
    // Loop through Items of a contract
    for (int j = 0; j < dataToConvert.getNumRows(); j++) {
      dataToConvert.setRow(j);
      itemJson = new JsonObject();
      itemJson.addProperty("clienteId", dataToConvert.getString("KUNNR"));
      itemJson.addProperty("facturaNo", dataToConvert.getString("XBLNR"));
      itemJson.addProperty("cuit", dataToConvert.getString("STCD1"));
      itemJson.addProperty("clienteName", dataToConvert.getString("NAME1"));
      itemJson.addProperty("tipoCbte", dataToConvert.getString("DOCCLS"));
      itemJson.addProperty("facturaLetra", dataToConvert.getString("J_1APRTCHR"));
      itemJson.addProperty("refSd", dataToConvert.getString("CAE_REF"));
      exportData.add(itemJson);
    }   // endfor
    
    return exportData;
    
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
	        
	        return object;
			
		} catch (JsonParseException e) {
			throw new RuntimeException("error parsing the json file at loadJsonFromFileToObject() method");
	        
		} catch (IOException e) {
			throw new RuntimeException("file not found in loadJsonFromFileToObject(): " + fileName);
		}
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
			JsonObject clxGtwInfo = loadJsonFromFileToObject("settings/clx_gateway_settings.json");  // linux
			JsonObject serverInfo = clxGtwInfo.get("MySqlCsi").getAsJsonObject();
		
			serverName = serverInfo.get("host").getAsString();
			serverPort = serverInfo.get("port").getAsString();
			userName   = serverInfo.get("userName").getAsString();
			password   = serverInfo.get("password").getAsString();
			
		} catch (RuntimeException e) {
			throw new RuntimeException("API-0024(E): no se pudo obtener las credenciales para acceder al MySql de CSI (" + e.getMessage() +")");
		}
		
		// Establecer la conexión
		try {
			String driver = "com.mysql.cj.jdbc.Driver";
			String url    = "jdbc:mysql://" + serverName +":"+ serverPort + "/fecred?useLegacyDatetimeCode=false&serverTimezone=America/Argentina/Buenos_Aires";
			Class.forName(driver);
			
			conn = DriverManager.getConnection(url, userName, password);
		} catch (Exception e) {
			throw new RuntimeException("API-0025(E): no se pudo establecer una conexión con el MySql de CSI (" + serverName + "): " + e.getMessage());
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
