package solidbase.http;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Response
{
	protected ResponseOutputStream out;
	protected ResponseWriter writer;
	protected PrintWriter printWriter;
	protected Map< String, List< String > > headers = new HashMap< String, List<String> >();
	protected boolean committed;
	protected int statusCode = 200;
	protected String statusMessage = "OK";

	public Response( OutputStream out )
	{
		this.out = new ResponseOutputStream( this, out );
	}

	public ResponseOutputStream getOutputStream()
	{
		return this.out;
	}

	public ResponseWriter getWriter()
	{
		if( this.writer != null )
			return this.writer;
		return getWriter( "ISO-8859-1" );
	}

	public ResponseWriter getWriter( String encoding )
	{
		if( this.writer != null )
		{
			if( this.writer.getEncoding().equals( encoding ) )
				return this.writer;
			this.writer.flush();
		}
		return this.writer = new ResponseWriter( this.out, encoding );
	}

	public PrintWriter getPrintWriter( String encoding )
	{
		return new PrintWriter( getWriter( encoding ) );
	}

	public void setHeader( String name, String value )
	{
		List< String > values = new ArrayList< String >();
		values.add( value );
		this.headers.put( name, values );
	}

	public void writeHeader( OutputStream out )
	{
		ResponseWriter writer = new ResponseWriter( out, "ISO-8859-1" );
		writer.write( "HTTP/1.1 " );
		writer.write( Integer.toString( this.statusCode ) );
		writer.write( " " );
		writer.write( this.statusMessage );
		writer.write( '\n' );
		for( Map.Entry< String, List< String > > entry : this.headers.entrySet() )
			for( String value : entry.getValue() )
			{
				writer.write( entry.getKey() );
				writer.write( ": " );
				writer.write( value );
				writer.write( '\n' );
			}
		writer.write( '\n' );
		this.committed = true;
	}

	public boolean isCommitted()
	{
		return this.committed;
	}

	public void setStatusCode( int code, String message )
	{
		this.statusCode = code;
		this.statusMessage = message;
	}

	public void reset()
	{
		this.out.clear();
		this.writer = null;
		this.statusCode = 200;
		this.statusMessage = "OK";
		this.headers.clear();
	}

	public void flush()
	{
		this.out.flush();
	}
}