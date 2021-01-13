package sap.utilities;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
//import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import java.util.*;
import javax.mail.*;
import javax.mail.internet.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sap.conn.jco.JCoContext;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoStructure;
import com.sap.conn.jco.JCoTable;

/**
   This program sets all the currencies of the actual day into SAP
   This program is executed by a CRON in a linux server all days at 20hs.
   
   The currencies are obtain calling the following API:
      https://api.coinmonitor.info/dolar/
   This API returns a JSON object (as a string):
   {
	"DOL_argentina" : "16.995",
	"DOL_argentina_compra" : "16.895",
	"DOL_brasil" : "3.2650",
	"DOL_mexico" : "17.9751",
	"DOL_colombia" : "3064.0000",
	"DOL_chile" : "667.1000",
	"DOL_venezuela" : "7671.97",
	"DOL_venezuela_flotante" : "2640.00",
	"DOL_uruguay" : "28.9300",
	"DOL_peru" : "3.2582",
	"ARS_EUR" : "19.3845",
	"time" : "1499733001",
	"updated" : "10-07-17 | 21:30:01"
	}
	
	The currencies to be calculated and added to SAP are:
		ARS -> USD  (venta)
		ARS -> USD  (compra)
		UYU -> USD  (venta)
		CLP -> USD  (venta)
		COP -> USD  (venta)
		PEN -> USD  (venta)
		MXN -> USD  (venta)
		BRL -> USD  (venta)
		VEL -> USD  (venta) venezolanos libre
		VEP -> USD  (venta) venezolanos oficial
		ARS -> EUR  (venta)
		USD -> EUR  (venta)
		MXN -> EUR  (venta)
		
 
*/
public class SetCurrencies {
 		
	public static void main(String[] args) throws RuntimeException, IOException {
		
		// Initialize variables
		JsonObject  currencies;
		String      outMsgCurrencies = "";
		String      outMsgErrors     = "";
		String      status           = "";

		// Set Date of process
		ZoneId        zonedId     = ZoneId.of( "America/Argentina/Buenos_Aires" );
		ZonedDateTime dateProcess = ZonedDateTime.now( zonedId );
		
		try {
				
			// Read the currencies from coinmonitor.info  API
			currencies = loadCurrenciesFromAPI();

			// for DEBUG
			//currencies = loadJsonFromFile();
			// for DEBUG

			// Initialize SAP Connection 
			JCoDestination sapDestination = JCoDestinationManager.getDestination("settings/SAPCONNECT");
			//JCoDestination sapDestination = JCoDestinationManager.getDestination("settings/SAPCONNECT");  // for PC local
			
			// Initialize SAP bapi routines
			JCoFunction fxnBapi   = setBapiFunction(sapDestination, "BAPI_EXCHRATE_CREATEMULTIPLE");
			JCoFunction fxnCommit = setBapiFunction(sapDestination, "BAPI_TRANSACTION_COMMIT");
		
			// Set the bapi IMPORT DATA
			outMsgCurrencies = setBapiImportData(fxnBapi, currencies);
	 		
			// Execute the bapi and get the result
			JCoContext.begin(sapDestination);

				// Execute the bapi
				fxnBapi.execute(sapDestination);
			
				// Get the bapi EXPORT/TABLE data
				outMsgErrors = getBapiExportData(fxnBapi);
				status       = outMsgErrors.substring(0, 1); 
				
				if ("SI".indexOf(status) >= 0) {
					// Commit transaction
					fxnCommit.execute(sapDestination);
					
				} else {
					// Rollback transaction
					JCoFunction fxnRollback = setBapiFunction(sapDestination, "BAPI_TRANSACTION_ROLLBACK");
					fxnRollback.execute(sapDestination);
					
				}
				
			JCoContext.end(sapDestination);
           	
		} catch (JCoException | RuntimeException e) {
			outMsgErrors = outMsgErrors + e.toString();
			
		} finally {
			
			// Open the LOG file to output the result messages: outMsgCurrencies  &  outMsgErrors
			FileWriter fw = new FileWriter("static/setCurrencies.log", true);
			//FileWriter fw = new FileWriter("C:\\temp\\setCurrencies.log", true);
			BufferedWriter bw = new BufferedWriter(fw);

      // Esta 2 lineas es para saber el directorio raiz donde está corriendo la aplicacion 
      final File file = new File(".");
      bw.write("file = " + file.getAbsoluteFile().getParent());

			// Write the Process Date
			outMsgCurrencies = "\r\n----------------------------------------------------------------------------------------------------\r\n"
					         + dateProcess + "\r\n"
					         + ">>>>> CURRENCIES FOR THE DAY:\r\n"
					         + outMsgCurrencies; 
			bw.write(outMsgCurrencies);
			if (!status.equals("S") || outMsgCurrencies.indexOf("API-0021") >= 0) {
				outMsgErrors = "\r\n>>>>> AN ERROR OR WARNING OCCUR:\r\n"
						     + outMsgErrors;
				// Write errors to LOG file
				bw.write(outMsgErrors);
			};
		    bw.close();
			
			// Send MAIL to advice of the errors
			if (!status.equals("S")) {
				SendEmail("gsansalone@claxson.com,itcorp@claxson.com,gonzalo.mtanoira@gmail.com,jvangelderen@claxson.com", "itcorp@claxson.com", "ERROR setting daily currencies in SAP", outMsgCurrencies + outMsgErrors);
			};
			
		}

	}

	/** ***********************************************************************************************************************
	 * Metodo: callInetSatAPI()
	 * Llama a la API  /api/FeedApi/GetProgramAsRunLogs
	 */
	private static JsonObject loadCurrenciesFromAPI () throws RuntimeException {
		  HttpURLConnection connection = null;

		  // Initialize
		  JsonObject jsonObject = new JsonObject();

		  try {
			  
		    //Create connection
		    URL url = new URL("https://api.coinmonitor.info/dolar/");
		    connection = (HttpURLConnection) url.openConnection();
		    connection.setRequestMethod("GET");
		    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
		    connection.setRequestProperty("Content-Language", "en-US");
		    connection.setRequestProperty("Content-Length", "0");  //Integer.toString(urlParameters.getBytes().length));
		    connection.setRequestProperty("Content-Type", "text/html");
			    
				connection.setUseCaches(false);
		    connection.setDoOutput(true);
				
			    // Load the AsRun events form the response.body (JSON structure)
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
          jsonObject = parser.parse(responseData).getAsJsonObject();
			    
		    } else {
			    throw new RuntimeException("API-0011(E): cannot get data from API https://api.coinmonitor.info/dolar/");
		    }
		    
		  } catch (JsonParseException e) {
			  throw new RuntimeException("JSON parser error in loadCurrenciesFromAPI(): " + e.toString());

		  } catch (IOException e) {
			  System.out.println(e.getMessage());  
			  System.out.println(e.getStackTrace());
			  e.printStackTrace();
			  throw new RuntimeException("HTTP error calling InetSat Server in loadCurrenciesFromAPI(): " + e.getMessage().toString());
			    
		  } catch (Exception e) {
			  throw new RuntimeException("in loadCurrenciesFromAPI(): " + e.toString());

		  } finally {
		    if (connection != null) {
		    	connection.disconnect();
		    }
		  }
		  
		  return jsonObject;
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
	 *  Method: toDate(date)
	 *  		date: yyyy-mm-dd
	 *  This method converts a DATE from dd-mm-yy  to  yyyymmdd  format
	*/
 	private static String toDate(String date) {
 		String retDate;
 		
 		retDate = "20" + date.substring(6, 8) + date.substring(3, 5) + date.substring(0, 2);
 		
 		return retDate;
 	}

	/** ***************************************************************************************************************
	 *  This method SETs the IMPORT DATA for the BAPI 
	*/
	private static String setBapiImportData(JCoFunction fxnBapi, JsonObject importData) throws RuntimeException {
		// importData representa todos las cotizaciones obtenidas de coinmonitor.info
		
		// Initialize Variables
		String auxString;
		BigDecimal auxValor;
		float  cero = 0.0f;
		float  factor = 1;
		String valid_from = toDate(importData.get("updated").getAsString().substring(0,8));
		float  value  = 0;
		String rtnMessage = "";
		
		// Set structure IMPORT
		try {
		
			fxnBapi.getImportParameterList().setValue("UPD_ALLOW",  'X');

		} catch (Exception e) {
			throw new RuntimeException("API-0012(E): setting data to the SAP structures IMPORT.upd_allow in setBapiImportData: " + e.getMessage().toString());
		}
			
		// Set table EXCHRATE_LIST 
		try {
			
			JCoTable items_EL = fxnBapi.getTableParameterList().getTable("EXCHRATE_LIST");
			// ARS -> USD  (compra)
			
			if (new BigDecimal(importData.get("DOL_argentina_compra").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      'G');
				items_EL.setValue("FROM_CURR",      "ARS");
				items_EL.setValue("TO_CURRNCY",     "USD");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("DOL_argentina_compra").getAsString());
		    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " ARS (compra)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    } else {
		    rtnMessage = rtnMessage + "1 USD = ??? ARS (compra) (API-0021(W): el valor DOL_argentina_compra es 0 (cero))\r\n"; 
	    };

			// ARS -> USD  (venta)
	    if (new BigDecimal(importData.get("DOL_argentina").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      'M');
				items_EL.setValue("FROM_CURR",      "ARS");
				items_EL.setValue("TO_CURRNCY",     "USD");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("DOL_argentina").getAsString());
		    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " ARS (venta)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    } else {
		    rtnMessage = rtnMessage + "1 USD = ??? ARS (venta) (API-0021(W): el valor DOL_argentina es 0 (cero))\r\n"; 
	    };

			// UYU -> USD  (venta)
	    if (new BigDecimal(importData.get("DOL_uruguay").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      'M');
				items_EL.setValue("FROM_CURR",      "UYU");
				items_EL.setValue("TO_CURRNCY",     "USD");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("DOL_uruguay").getAsString());
		    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " UYU (venta)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    } else {
		    rtnMessage = rtnMessage + "1 USD = ??? UYU (venta) (API-0021(W): el valor DOL_uruguay es 0 (cero))\r\n"; 
	    };

			// CLP -> USD  (venta)
	    if (new BigDecimal(importData.get("DOL_chile").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      'M');
				items_EL.setValue("FROM_CURR",      "CLP");
				items_EL.setValue("TO_CURRNCY",     "USD");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("DOL_chile").getAsString());
		    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " CLP (venta)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    } else {
		    rtnMessage = rtnMessage + "1 USD = ??? CLP (venta) (API-0021(W): el valor DOL_chile es 0 (cero))\r\n"; 
	    };

			// COP -> USD  (venta)
	    if (new BigDecimal(importData.get("DOL_colombia").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      'M');
				items_EL.setValue("FROM_CURR",      "COP");
				items_EL.setValue("TO_CURRNCY",     "USD");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("DOL_colombia").getAsString());
		    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " COP (venta)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    } else {
		    rtnMessage = rtnMessage + "1 USD = ??? COP (venta) (API-0021(W): el valor DOL_colombia es 0 (cero))\r\n"; 
	    };

			// PEN -> USD  (venta)
	    if (new BigDecimal(importData.get("DOL_peru").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      'M');
				items_EL.setValue("FROM_CURR",      "PEN");
				items_EL.setValue("TO_CURRNCY",     "USD");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("DOL_peru").getAsString());
		    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " PEN (venta)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    } else {
		    rtnMessage = rtnMessage + "1 USD = ??? PEN (venta) (API-0021(W): el valor DOL_peru es 0 (cero))\r\n"; 
	    };

			// MXN -> USD  (venta)
	    if (new BigDecimal(importData.get("DOL_mexico").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      'M');
				items_EL.setValue("FROM_CURR",      "MXN");
				items_EL.setValue("TO_CURRNCY",     "USD");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("DOL_mexico").getAsString());
		    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " MXN (venta)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    } else {
		    rtnMessage = rtnMessage + "1 USD = ??? MXN (venta) (API-0021(W): el valor DOL_mexico es 0 (cero))\r\n"; 
	    };

			// BRL -> USD  (venta)
	    if (new BigDecimal(importData.get("DOL_brasil").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      'M');
				items_EL.setValue("FROM_CURR",      "BRL");
				items_EL.setValue("TO_CURRNCY",     "USD");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("DOL_brasil").getAsString());
		    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " BRL (venta)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    } else {
		    rtnMessage = rtnMessage + "1 USD = ??? BRL (venta) (API-0021(W): el valor DOL_brasil es 0 (cero))\r\n"; 
	    };

			// VEL -> USD  (venta)  venezolanos libre
	    if (new BigDecimal(importData.get("DOL_venezuela").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      'M');
				items_EL.setValue("FROM_CURR",      "VEL");
				items_EL.setValue("TO_CURRNCY",     "USD");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("DOL_venezuela").getAsString());
		    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " VEL (venta libre)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  factor);
		    items_EL.setValue("TO_FACTOR_V",    1);
	    } else {
		    rtnMessage = rtnMessage + "1 USD = ??? VEL (venta libre) (API-0021(W): el valor DOL_venezuela es 0 (cero))\r\n"; 
	    };

			// VEB -> USD  (venta)  venezolanos oficial
		    /**
		    if (new BigDecimal(importData.get("DOL_venezuela_flotante").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
			    items_EL.appendRow();
    			items_EL.setValue("RATE_TYPE",      'M');
    			items_EL.setValue("FROM_CURR",      "VEB");
    			items_EL.setValue("TO_CURRNCY",     "USD");
			    items_EL.setValue("VALID_FROM",     valid_from);
			    value  = Float.parseFloat(importData.get("DOL_venezuela_flotante").getAsString());
			    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " VEB (venta oficial)\r\n"; 
			    factor = toFactor('F', value);
			    value  = toFactor('V', value);
			    items_EL.setValue("EXCH_RATE_V",    value);
			    items_EL.setValue("FROM_FACTOR_V",  1);
			    items_EL.setValue("TO_FACTOR_V",    factor);
		    } else {
			    rtnMessage = rtnMessage + "1 USD = ??? VEB (venta oficial) (API-0021(W): el valor DOL_venezuela_flotante es 0 (cero))\r\n"; 
		    };
		    **/

			// VES -> USD  (venta)  venezolanos sobernaos
	    if (new BigDecimal(importData.get("DOL_venezuela_soberano").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      'M');
				items_EL.setValue("FROM_CURR",      "VES");
				items_EL.setValue("TO_CURRNCY",     "USD");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("DOL_venezuela_soberano").getAsString());
		    rtnMessage = rtnMessage + "1 USD = " + String.valueOf(value) + " VES (soberano)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  factor);
		    items_EL.setValue("TO_FACTOR_V",    1);
	    } else {
		    rtnMessage = rtnMessage + "1 USD = ??? VEL (venta libre) (API-0021(W): el valor DOL_venezuela es 0 (cero))\r\n"; 
	    };

			// ARS -> EUR  (venta)
	    if (new BigDecimal(importData.get("ARS_EUR").getAsString()).compareTo(BigDecimal.ZERO) != 0) {
				items_EL.appendRow();
				items_EL.setValue("RATE_TYPE",      "EURX");
				items_EL.setValue("FROM_CURR",      "ARS");
				items_EL.setValue("TO_CURRNCY",     "EUR");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("ARS_EUR").getAsString());
		    rtnMessage = rtnMessage + "1 EUR = " + String.valueOf(value) + " ARS (venta)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    } else {
		    rtnMessage = rtnMessage + "1 EUR = ??? ARS (venta) (API-0021(W): el valor ARS_EUR es 0 (cero))\r\n"; 
	    };

			// USD -> EUR  (venta)
	    if (new BigDecimal(importData.get("DOL_argentina").getAsString()).compareTo(BigDecimal.ZERO) == 0) {
		    rtnMessage = rtnMessage + "1 EUR = ??? USD (venta) (API-0021(W): DOL_argentina = 0 (cero))\r\n"; 
	    } else if (new BigDecimal(importData.get("ARS_EUR").getAsString()).compareTo(BigDecimal.ZERO) == 0) {
		    rtnMessage = rtnMessage + "1 EUR = ??? USD (venta) (API-0021(W): ARS_EUR = 0 (cero))\r\n"; 
	    } else  {
  			items_EL.appendRow();
  			items_EL.setValue("RATE_TYPE",      "EURX");
  			items_EL.setValue("FROM_CURR",      "USD");
  			items_EL.setValue("TO_CURRNCY",     "EUR");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("ARS_EUR").getAsString()) 
		    	   / Float.parseFloat(importData.get("DOL_argentina").getAsString());
		    rtnMessage = rtnMessage + "1 EUR = " + String.valueOf(value) + " USD (venta)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    };

	    // MXN -> EUR  (venta)
	    if (new BigDecimal(importData.get("DOL_argentina").getAsString()).compareTo(BigDecimal.ZERO) == 0) {
		    rtnMessage = rtnMessage + "1 EUR = ??? MXN (venta) (API-0021(W): DOL_argentina = 0 (cero))\r\n"; 
	    } else if (new BigDecimal(importData.get("ARS_EUR").getAsString()).compareTo(BigDecimal.ZERO) == 0) {
		    rtnMessage = rtnMessage + "1 EUR = ??? MXN (venta) (API-0021(W): ARS_EUR = 0 (cero))\r\n"; 
	    } else if (new BigDecimal(importData.get("DOL_mexico").getAsString()).compareTo(BigDecimal.ZERO) == 0) {
		    rtnMessage = rtnMessage + "1 EUR = ??? MXN (venta) (API-0021(W): DOL_mexico = 0 (cero))\r\n"; 
	    } else  {
  			items_EL.appendRow();
  			items_EL.setValue("RATE_TYPE",      "EURX");
  			items_EL.setValue("FROM_CURR",      "MXN");
  			items_EL.setValue("TO_CURRNCY",     "EUR");
		    items_EL.setValue("VALID_FROM",     valid_from);
		    value  = Float.parseFloat(importData.get("ARS_EUR").getAsString())
		    	   / Float.parseFloat(importData.get("DOL_argentina").getAsString())
	    	       * Float.parseFloat(importData.get("DOL_mexico").getAsString());
		    rtnMessage = rtnMessage + "1 EUR = " + String.valueOf(value) + " MXN (venta)\r\n"; 
		    factor = toFactor('F', value);
		    value  = toFactor('V', value);
		    items_EL.setValue("EXCH_RATE_V",    value);
		    items_EL.setValue("FROM_FACTOR_V",  1);
		    items_EL.setValue("TO_FACTOR_V",    factor);
	    };

		} catch (NullPointerException e) {
			throw new RuntimeException("API-0014(E): JSON parse error in setBapiImportData method. Error: NullPointer while setting EXCHRATE_LIST structures in SAP ");

		} catch (Exception e) {
			throw new RuntimeException(rtnMessage + "\r\nAPI-0012(E): setting data to the SAP structures EXCHRATE_LIST in setBapiImportData: " + e.getMessage().toString());
		}
		
		return rtnMessage;
	}

	/** ***************************************************************************************************************
	 *  This method GETs the EXPORT DATA from the BAPI BAPI_ACC_GL_POSTING_POST
	*/
	private static String getBapiExportData(JCoFunction fxnBapi) throws RuntimeException {

		// initialize variables
		String outBapiData = "";
		String status;
		int    numErrors   = 0;   // amount of errors.
		
		// Read from BAPI: table RETURN
		JCoTable tableReturn = fxnBapi.getTableParameterList().getTable("RETURN");
		
		// Read the 1st. record to see the status of the transaction
      tableReturn.setRow(0);
      status = tableReturn.getString("TYPE");

        // Append all records from table RETURN in a JSON structure
    	outBapiData = "[\r\n";
     	for (int i = 0; i < tableReturn.getNumRows(); i++) {
     		
     		if (i > 0) {
           	outBapiData =  outBapiData + ",\r\n"; 
     		}
     		
     		numErrors += 1;
     		tableReturn.setRow(i);
         	outBapiData =  outBapiData 
         		   + "{\"id\":\""     + tableReturn.getString("ID")   + "\",\r\n" 
         		   + "\"number\":\""  + tableReturn.getString("NUMBER") + "\",\r\n"
         		   + "\"message\":\"" + tableReturn.getString("MESSAGE") + "\"\r\n"
         		   + "}";
      }
     	outBapiData =  outBapiData + "]"; 
     	
     	// If the status = 'I' and there is only 1 error message, set the status to 'S'
     	// This means that only a few currencies were save in SAP and others don't.
     	// Setting the status to 'I' will send a mail to take care of the possible errors.
      if ( status.equals("I") && numErrors <= 1 ) {
      	status = "S";
      }
      return status + "-" + outBapiData;
	}

	/** ***********************************************************************************************************************
	 * Function: toFactor(char rtnProp, float value)
	 * 
	 * This routine convert the float parameter value with a factor of 10, 100, 1000, etc. if its value is greater or equal 10000, etc.
	 *  
	 *        0 >= value <=   9.999  -> return = value / 1  (NO CHANGE)
	 *   10.000 >= value <=  99.999  -> return = value / 10
	 *  100.000 >= value <= 999.999  -> return = value / 100
	 *  etc.
	 *  
	 *  @rtnProp:
	 *    ='V': return value
	 *    ='F': return factor
	 */
	private static float toFactor(char rtnProp, float value) {
		
		float factor = 1;
		
		if (value < 10000) {
			factor = 1;
		} else if (value < 100000) {
			factor = 10;
		} else if (value < 1000000) {
			factor = 100;
		} else if (value < 10000000) {
			factor = 1000;
		}
		
		if (rtnProp == 'V') {
			return (value / factor);
		} else {
			return factor;
		}
	}

	/** ***********************************************************************************************************************
	 * Function: lPad
	 * Esta rutina hace un left padding del caracter enviado en 'c' hasta completar 'n' digitos
	 * 
	 */
	private static String lPad(String ori, int n, char c) {
    int i; 
    String a = ori;
    for (i = 0; i < n - ori.length(); i++) { 
    	a = c + a;
    };
    return a;
	}    
	// File Name SendEmail.java

	/**
	**/
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
			message.addRecipients(Message.RecipientType.TO, to_recipients);   

			// Set Subject: header field
			message.setSubject(subject);

			// Now set the actual message
			message.setText(body);

			// Send message
			Transport.send(message);
			// System.out.println("Sent message successfully....");
         
		}catch (MessagingException e) {
			throw new RuntimeException("API-0006(E): error sending mail in java program SetCurrencies: " + e.getMessage());
		}
      
	}
      
}
