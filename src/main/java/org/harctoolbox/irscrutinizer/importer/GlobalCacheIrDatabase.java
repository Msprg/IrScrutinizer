/*
Copyright (C) 2013 Bengt Martensson.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or (at
your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see http://www.gnu.org/licenses/.
*/

package org.harctoolbox.irscrutinizer.importer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.stream.JsonParser;
import org.harctoolbox.girr.Command;
import org.harctoolbox.girr.Remote;
import org.harctoolbox.girr.RemoteSet;
import org.harctoolbox.harchardware.ir.GlobalCache;
import org.harctoolbox.ircore.InvalidArgumentException;
import org.harctoolbox.ircore.IrSignal;
import org.harctoolbox.irscrutinizer.Props;

public class GlobalCacheIrDatabase extends DatabaseImporter implements IRemoteSetImporter {

    public final static String globalCacheIrDatabaseHost = "irdatabase.globalcache.com";
    private final static String path = "/api/v1/";
    private final static String globalCacheDbOrigin = globalCacheIrDatabaseHost;

    private static String httpEncode(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s, "utf-8").replaceAll("\\+", "%20");
    }

    @SuppressWarnings("UseOfSystemOutOrSystemErr")
    public static void main(String[] args) {
        Props props = new Props(null);
        Importer.setProperties(props);
        try {
            GlobalCacheIrDatabase gcdb = new GlobalCacheIrDatabase(props.getGlobalCacheApiKey(), true);
            System.out.println(gcdb.getManufacturers());
            System.out.println(gcdb.getDeviceTypes("philips"));
            System.out.println(gcdb.getCodeset("sony", "laser disc"));
            System.out.println(gcdb.getCommands("sony", "laser disc", "201"));
        } catch (IOException | InvalidArgumentException ex) {
            System.err.println(ex.getMessage());
        }
    }

    private boolean verbose = false;

    private final String apiKey;
    private Map<String, String> manufacturerMap = null;
    private String manufacturer;
    private String deviceType;
    private RemoteSet remoteSet;
    public GlobalCacheIrDatabase(String apiKey, boolean verbose) {
        super(globalCacheDbOrigin);
        this.apiKey = apiKey;
        this.verbose = verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    @SuppressWarnings("UseOfSystemOutOrSystemErr")
    private InputStreamReader getReader(String str) throws IOException {
        URL url = new URL("http", globalCacheIrDatabaseHost, path + apiKey + "/" + str);
        if (verbose)
            System.err.println("Opening " + url.toString());
        URLConnection urlConnection = url.openConnection();
        return new InputStreamReader(urlConnection.getInputStream(), Charset.forName("US-ASCII"));
    }

    private JsonValue readFrom(String str) throws IOException {
        return readFrom(getReader(str));
    }

    private JsonValue readFrom(Reader reader) throws IOException {
        JsonParser parser = Json.createParser(reader);
        JsonParser.Event x = parser.next();
        return parser.getValue();
    }

    private Map<String, String> getMap(String urlFragment, String keyName, String valueName) throws IOException {
        JsonArray array = readFrom(urlFragment).asJsonArray();
        Map<String,String> map = new HashMap<>(64);
        for (JsonValue val : array) {
            JsonObject obj = val.asJsonObject();
            map.put(obj.getString(keyName), obj.getString(valueName));
        }
        return map;
    }

    private void loadManufacturers() throws IOException {
        manufacturerMap = getMap("manufacturers", "Manufacturer", "Key");
    }

    public Collection<String> getManufacturers() throws IOException {
        if (manufacturerMap == null)
            loadManufacturers();
        return manufacturerMap.values();
    }

    public Collection<String> getDeviceTypes(String manufacturerKey) throws IOException {
        return getMap("manufacturers/" + httpEncode(manufacturerKey) + "/devicetypes", "DeviceType", "Key").values();
    }

    public Collection<String> getCodeset(String manufacturerKey, String deviceTypeKey) throws IOException {
        return getMap("manufacturers/" + httpEncode(manufacturerKey) + "/devicetypes/" + httpEncode(deviceTypeKey) + "/codesets",
                "Codeset", "Key").values();
    }

    public ArrayList<Command> getCommands(String manufacturerKey, String deviceTypeKey, String codeSet) throws IOException, InvalidArgumentException {
        load(manufacturerKey, deviceTypeKey, codeSet);
        return getCommands();//codesMap;
    }

    public void load(String manufacturerKey, String deviceTypeKey, String codeSet) throws IOException, InvalidArgumentException {
        clearCommands();
        manufacturer = manufacturerKey;
        deviceType = deviceTypeKey;
        JsonArray array = readFrom("manufacturers/" + httpEncode(manufacturerKey) + "/devicetypes/" + httpEncode(deviceTypeKey) + "/codesets/" + codeSet).asJsonArray();
        for (JsonValue val : array) {
            JsonObject obj = val.asJsonObject();
            String str = obj.getString("IRCode");
            IrSignal irSignal =  GlobalCache.parse(GlobalCache.sendIrPrefix + ",1,1," + str);
            if (irSignal != null) {
                String keyName = obj.getString("KeyName");
                Command cmd = new Command(keyName,
                        "GCDB: " + manufacturer + "/" + deviceType + "/" + codeSet, irSignal);
                addCommand(cmd);
            }
        }
        Remote.MetaData metaData = new Remote.MetaData(manufacturer + "_" + deviceType + "_" + codeSet, // name,
                null, // displayName
                manufacturer,
                null, // model,
                deviceType, // deviceClass,
                null // remoteName,
        );
        Remote remote = new Remote(metaData,
                null, //java.lang.String comment,
                null,
                getCommandIndex(),
                null //java.util.HashMap<java.lang.String,java.util.HashMap<java.lang.String,java.lang.String>> applicationParameters)
        );

        remoteSet = new RemoteSet(getCreatingUser(), origin, remote);
    }

    @Override
    public RemoteSet getRemoteSet() {
        return remoteSet;
    }

    @Override
    public String getFormatName() {
        return "Global Caché IR Database";
    }

    @Override
    public Remote.MetaData getMetaData() {
        return remoteSet.iterator().next().getMetaData();
    }
}
