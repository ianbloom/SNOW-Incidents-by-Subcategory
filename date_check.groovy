import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import groovy.json.JsonSlurper;
import groovy.json.JsonOutput;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.LaxRedirectStrategy;
import groovy.xml.MarkupBuilder;
import java.text.SimpleDateFormat;
import groovy.time.TimeCategory;

def user     = 'sales.engineers'
def pass     = '4ZXHxaADpwbNFdb#~'
def instance = 'ven01523'
def endpoint = '/now/v1/table/incident'

// def user     = hostProps.get("snow.user")
// def pass     = hostProps.get("snow.pass")
// def instance = hostProps.get("snow.instance")
// def endpoint = '/now/v1/table/incident'

def widget_id  = '14324'

def access_id  = 'vbV6wrd2UsnQBz6F82UT'
def access_key = 'j5t8wgRcs~+73!7(9uCHx9UndgL{34FP5hd(sdeL'
def account    = 'salesdemo'

// def widget_id  = hostProps.get("widget.id")

// def access_id  = hostProps.get("lmaccess.id")
// def access_key = hostProps.get("lmaccess.key")
// def account    = hostProps.get("lmaccount")

def date = new Date()
def sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
def current_day = sdf.format(date)

// THIS ONE IS GLOBAL ON PURPOSE
days_in_week = 7

// Initialize result table
def result_table = TABLEGEN(user, pass, instance)

// Each of these will be run through sysparam_oneday to produce proper query
def dayarray = DAYPARAMGEN()

// count will be used to look up in DAYLOOKUPGEN
def count = 0
def day_lookup = DAYLOOKUPGEN()

dayarray.each { date_string ->
    def sysparm = SYSPARAM_ONEDAY(date_string)
    def response = SNOWGET(user, pass, instance, endpoint, sysparm)
    def response_body = response['body']

    def day_of_week = day_lookup[count.toString()]
    def response_json = new JsonSlurper().parseText(response_body)
    def result_array = response_json['result']

    // Now add to the correct subcat of correct day for each incident
    result_array.each { incident ->
        def subcat = incident['subcategory']
        result_table[day_of_week][subcat] += 1
    }
    
    count += 1
}

def writer = new StringWriter()
def html = new MarkupBuilder(writer)

def table_title = 'Incidents This Week by Subcategory'

def loop_helper = result_table['Sunday']
def loop_num = loop_helper.size()

def total_dict = TOTAL_CALC(result_table)

html.table{
    caption(table_title)
    thead{
        tr{
            // Empty first header to allow columns
            th('')
            th('Total')
            result_table.each{ day, dict ->
                th(day)
            }
        }
    }
    tbody{
        loop_helper.each { sub_cat, throw_away ->
            tr{
                td(sub_cat)
                td(total_dict[sub_cat])
                result_table.each{ day, sub_dict ->
                    td(sub_dict[sub_cat])
                }
            }
        }
    }
}

def css_dict = CSSGEN()
def html_string = css_dict['header'] + writer + css_dict['footer']

////////////////
// POST TO LM //
////////////////

resource_path = "/dashboard/widgets/${widget_id}"
query_params  = ''
data          = ''

get_result = LMGET(access_id, access_key, account, resource_path, query_params, data)
response_body = get_result['body']
get_json = new JsonSlurper().parseText(response_body);
get_json['content'] = html_string

data = JsonOutput.toJson(get_json);
put_result = LMPUT(access_id, access_key, account, resource_path, query_params, data)

if(put_result['code'] == 200) {
    return 0;
}
else {
    return 1;
}

def TOTAL_CALC(_result_table) {
    def total_dict = [:]
    def key_helper = _result_table['Sunday']

    key_helper.each { key, value ->
        total_dict[key] = 0
    }

    _result_table.each{ day, sub_dict ->
        sub_dict.each{ sub_cat, count ->
            total_dict[sub_cat] += count
        }
    }
    return total_dict
}
def SYSPARAM_ONEDAY(_date_string) {
    // this will calculate queryparams for every single day
    def date_split = _date_string.split()
    def sysparm_query = "?sysparm_query=sys_created_onBETWEENjavascript:gs.dateGenerate('${date_split[0]}','00:00:00')@javascript:gs.dateGenerate('${date_split[0]}','23:59:59')"

    return sysparm_query
}

def LASTSUNDAY() {
    def cal = Calendar.instance

    while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
        cal.add(Calendar.DAY_OF_WEEK, -1)
    }

    Date last_sunday = cal.time

    // print the date in yyyy-MM-dd format
    sunday_string = last_sunday.format("yyyy-MM-dd HH:mm:ss")

    return sunday_string
}

def AUTHBUILD(_user, _pass) {
    // Put user and pass together and base64 encode, return exact 'Authorization' header
    def combo = "${_user}:${_pass}"
    def encoded_combo = combo.bytes.encodeBase64();
    def auth_string = "Basic ${encoded_combo}"

    return auth_string
}

def SNOWGET(_user, _pass, _instance, _endpoint, _sysparm_query) {
    // Initialize responseDict
    responseDict = [:]

    // Build URL from input
    def url = "https://${_instance}.service-now.com/api${_endpoint}${_sysparm_query}"

    // Encode 'Authorization' header
    def auth_string = AUTHBUILD(_user, _pass)

    // Instantiate client
    CloseableHttpClient httpclient = HttpClients.createDefault();
	http_request = new HttpGet(url);
	http_request.addHeader("Authorization", auth_string);
	http_request.addHeader("Accept", "application/json");
	response = httpclient.execute(http_request);
	responseBody = EntityUtils.toString(response.getEntity());
	code = response.getStatusLine().getStatusCode();

	responseDict['code'] = code;
	responseDict['body'] = responseBody
	
	return responseDict;
}

def CSSGEN() {
    def html_header = '''<html>
    <head>
        <style>
        caption {
            font-family: Avenir, Helvetica, Arial, sans-serif;
            font-weight: bold;
            font-variant: small-caps;
            font-size: 200%;
            border: 1px solid #ddd;
            padding: 2px;
            border-collapse: collapse;
        }
        table {
            width: 100%;
            height: 100%;
        }
        tr, td, th {
            font-family: Avenir, Helvetica, Arial, sans-serif;
            border-collapse: collapse;
        }

        td, th {
            border: 1px solid #ddd;
            padding: 2px;
        }

        tr:nth-child(even) {background-color: #f2f2f2;}

        tr:hover {background-color: #ddd;}

        th {
            padding-top: 12px;
            padding-bottom: 12px;
            text-align: center;
            background-color: #037DF8;
            color: white;
        }
        </style>
    </head>
    <body>
    '''

    def html_footer = '''
    </body>
    </html>
    '''

    def css_dict = [:]
    css_dict['header'] = html_header
    css_dict['footer'] = html_footer

    return css_dict
}

def DAYLOOKUPGEN() {
    def day_dict = [:]
    day_dict['0'] = 'Sunday'
    day_dict['1'] = 'Monday'
    day_dict['2'] = 'Tuesday'
    day_dict['3'] = 'Wednesday'
    day_dict['4'] = 'Thursday'
    day_dict['5'] = 'Friday'
    day_dict['6'] = 'Saturday'

    return day_dict
}

def DAYPARAMGEN() {
    // This gives us an array of appropriate sysparams from last sunday until saturday
    // This does not imply that all of these days have data, we will error handle that later

    def day_param_array = []
    def cal = Calendar.instance

    while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
        cal.add(Calendar.DAY_OF_WEEK, -1)
    }

    for(i = 0; i < days_in_week; i++) {
        Date holder = cal.time
        holder_string = holder.format("yyyy-MM-dd HH:mm:ss")
        day_param_array.add(holder_string)
        cal.add(Calendar.DAY_OF_WEEK, 1)
    }

    return day_param_array
}

def ROWGEN(_user, _pass, _instance) {
    def endpoint = '/now/table/sys_choice'

    def sys_param = '?sysparm_query=name=incident%5Eelement=subcategory'

    response = SNOWGET(_user, _pass, _instance, endpoint, sys_param)
    response_body = response['body']
    response_code = response['code']
    response_json = new JsonSlurper().parseText(response_body);
    result_array = response_json['result']

    def label_array = []
    result_array.each{ dict ->
        label = dict['value']
        label_array.add(label)
    }

    return label_array
}

def TABLEGEN(_user, _pass, _instance) {
    // This function creates a dict with keys as the day of the week
    // the value is a dict, the keys of the subdict are subcategories, value is count of tix

    def lookup = DAYLOOKUPGEN()
    def row_array = ROWGEN(_user, _pass, _instance)

    table_dict = [:]
    for(i = 0; i < 7; i++) {
        key = i.toString()
        day = lookup[key]
        table_dict[day] = [:]

        row_array.each { subcat ->
            table_dict[day][subcat] = 0
        }
    }

    return table_dict
}

def LMPUT(_accessId, _accessKey, _account, _resourcePath, _queryParameters, _data) {

	// Initialize dictionary to hold response code and response body
	responseDict = [:];

	// Construcst URL to POST to from specified input
	url = 'https://' + _account + '.logicmonitor.com' + '/santaba/rest' + _resourcePath + _queryParameters;

	StringEntity params = new StringEntity(_data,ContentType.APPLICATION_JSON);

	// Get current time
	epoch = System.currentTimeMillis();

	// Calculate signature
	requestVars = 'PUT' + epoch + _data + _resourcePath;

	hmac = Mac.getInstance('HmacSHA256');
	secret = new SecretKeySpec(_accessKey.getBytes(), 'HmacSHA256');
	hmac.init(secret);
	hmac_signed = Hex.encodeHexString(hmac.doFinal(requestVars.getBytes()));
	signature = hmac_signed.bytes.encodeBase64();

	// HTTP Get
	CloseableHttpClient httpclient = HttpClients.createDefault();
	http_request = new HttpPut(url);
	http_request.addHeader("Authorization" , "LMv1 " + _accessId + ":" + signature + ":" + epoch);
	http_request.setHeader("Accept", "application/json");
	http_request.setHeader("Content-type", "application/json");
	http_request.addHeader("X-Version" , "2");
	http_request.setEntity(params);
	response = httpclient.execute(http_request);
	responseBody = EntityUtils.toString(response.getEntity());
	code = response.getStatusLine().getStatusCode();

	responseDict['code'] = code;
	responseDict['body'] = responseBody
	
	return responseDict;
}

def LMGET(_accessId, _accessKey, _account, _resourcePath, _queryParameters, _data) {
	// DATA SHOULD BE EMPTY
	// Initialize dictionary to hold response code and response body
	responseDict = [:];

	// Construcst URL to POST to from specified input
	url = 'https://' + _account + '.logicmonitor.com' + '/santaba/rest' + _resourcePath + _queryParameters;

	// Get current time
	epoch = System.currentTimeMillis();

	// Calculate signature
	requestVars = 'GET' + epoch + _data + _resourcePath;

	hmac = Mac.getInstance('HmacSHA256');
	secret = new SecretKeySpec(_accessKey.getBytes(), 'HmacSHA256');
	hmac.init(secret);
	hmac_signed = Hex.encodeHexString(hmac.doFinal(requestVars.getBytes()));
	signature = hmac_signed.bytes.encodeBase64();

	// HTTP Get
	CloseableHttpClient httpclient = HttpClients.createDefault();
	http_request = new HttpGet(url);
	http_request.addHeader("Authorization" , "LMv1 " + _accessId + ":" + signature + ":" + epoch);
	http_request.addHeader("X-Version" , "2");
	response = httpclient.execute(http_request);
	responseBody = EntityUtils.toString(response.getEntity());
	code = response.getStatusLine().getStatusCode();

	responseDict['code'] = code;
	responseDict['body'] = responseBody
	
	return responseDict;
}