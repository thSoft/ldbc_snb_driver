package com.ldbc.driver.runner;

public class OperationHandlerExecutorException extends Exception
{
    private static final long serialVersionUID = -5243628117143814942L;

    public OperationHandlerExecutorException( String message, Throwable cause )
    {
        super( message, cause );
    }

    public OperationHandlerExecutorException( String message )
    {
        super( message );
    }

    public OperationHandlerExecutorException( Throwable cause )
    {
        super( cause );
    }

    public OperationHandlerExecutorException()
    {
        super();
    }

}